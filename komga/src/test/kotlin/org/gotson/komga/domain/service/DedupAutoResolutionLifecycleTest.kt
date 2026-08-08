package org.gotson.komga.domain.service

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.model.DedupResolutionPlan
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DedupAutoResolutionLifecycleTest {
  private val dedupRepository = mockk<DedupRepository>()
  private val resolutionRepository = mockk<DedupResolutionRepository>()
  private val suggestionPlanner = mockk<DedupSuggestionPlanner>()
  private val resolutionLifecycle = mockk<DedupResolutionLifecycle>()
  private val lifecycle = DedupAutoResolutionLifecycle(dedupRepository, resolutionRepository, suggestionPlanner, resolutionLifecycle)

  @BeforeEach
  fun clearMockState() {
    clearMocks(dedupRepository, resolutionRepository, suggestionPlanner, resolutionLifecycle)
  }

  @Test
  fun `inactive settings never inspect or submit clusters`() {
    listOf(
      DedupLibrarySettings("library", enabled = false, autoResolveSuggestions = true),
      DedupLibrarySettings("library", enabled = true, paused = true, autoResolveSuggestions = true),
      DedupLibrarySettings("library", enabled = true, autoResolveSuggestions = false),
    ).forEach { settings ->
      clearMocks(dedupRepository, resolutionRepository, suggestionPlanner, resolutionLifecycle)
      every { dedupRepository.findLibrarySettings("library") } returns settings

      assertThat(lifecycle.submitBatch("library")).isEqualTo(DedupAutoResolutionBatchResult(0, 0, false))

      verify(exactly = 0) { dedupRepository.findUnresolvedClusters(any(), any(), any()) }
      verify(exactly = 0) { resolutionLifecycle.createSuggested(any(), any(), any()) }
    }
  }

  @Test
  fun `one batch submits at most twenty resolutions and requests continuation`() {
    val candidates = (1..21).map { cluster("cluster-$it", 1) }
    activeSettings()
    every { dedupRepository.findUnresolvedClusters("library", 0, 100) } returns candidates
    every { resolutionRepository.hasResolutionAttempt(any(), any(), DedupResolutionMode.SUGGESTED, DEDUP_AUTO_RESOLUTION_ACTOR) } returns false
    every { suggestionPlanner.evaluate(any<DedupClusterWithMembers>()) } returns suggestion()
    every { resolutionLifecycle.createSuggested(any(), any(), DEDUP_AUTO_RESOLUTION_ACTOR) } returns mockk<DedupResolution>()

    val result = lifecycle.submitBatch("library")

    assertThat(result).isEqualTo(DedupAutoResolutionBatchResult(20, 20, true))
    verify(exactly = 20) { resolutionLifecycle.createSuggested(any(), 1, DEDUP_AUTO_RESOLUTION_ACTOR) }
  }

  @Test
  fun `attention attempted and suggestionless clusters are skipped while a revised cluster is submitted`() {
    val attempted = cluster("attempted", 1)
    val attention = cluster("attention", 1, DedupClusterStatus.NEEDS_ATTENTION)
    val suggestionless = cluster("suggestionless", 1)
    val revised = cluster("attempted", 2)
    activeSettings()
    every { dedupRepository.findUnresolvedClusters("library", 0, 100) } returns listOf(attempted, attention, suggestionless, revised)
    every {
      resolutionRepository.hasResolutionAttempt("attempted", 1, DedupResolutionMode.SUGGESTED, DEDUP_AUTO_RESOLUTION_ACTOR)
    } returns true
    every { resolutionRepository.hasResolutionAttempt(match { it != "attempted" }, any(), any(), any()) } returns false
    every {
      resolutionRepository.hasResolutionAttempt("attempted", 2, DedupResolutionMode.SUGGESTED, DEDUP_AUTO_RESOLUTION_ACTOR)
    } returns false
    every { suggestionPlanner.evaluate(match<DedupClusterWithMembers> { it.cluster.id == "suggestionless" }) } returns DedupSuggestion(null)
    every { suggestionPlanner.evaluate(match<DedupClusterWithMembers> { it.cluster.revision == 2L }) } returns suggestion()
    every { resolutionLifecycle.createSuggested("attempted", 2, DEDUP_AUTO_RESOLUTION_ACTOR) } returns mockk<DedupResolution>()

    val result = lifecycle.submitBatch("library")

    assertThat(result).isEqualTo(DedupAutoResolutionBatchResult(1, 1, false))
    verify(exactly = 1) { resolutionLifecycle.createSuggested("attempted", 2, DEDUP_AUTO_RESOLUTION_ACTOR) }
  }

  @Test
  fun `a persisted failed attempt does not block the next cluster`() {
    activeSettings()
    every { dedupRepository.findUnresolvedClusters("library", 0, 100) } returns listOf(cluster("failed", 1), cluster("next", 1))
    every { resolutionRepository.hasResolutionAttempt(any(), any(), any(), any()) } returns false
    every { suggestionPlanner.evaluate(any<DedupClusterWithMembers>()) } returns suggestion()
    every { resolutionLifecycle.createSuggested("failed", 1, DEDUP_AUTO_RESOLUTION_ACTOR) } throws
      DedupResolutionExecutionException("resolution-failed", "QUEUE_FAILED", false, "queue unavailable")
    every { resolutionLifecycle.createSuggested("next", 1, DEDUP_AUTO_RESOLUTION_ACTOR) } returns mockk<DedupResolution>()

    val result = lifecycle.submitBatch("library")

    assertThat(result).isEqualTo(DedupAutoResolutionBatchResult(2, 1, false))
    verify(exactly = 1) { resolutionLifecycle.createSuggested("next", 1, DEDUP_AUTO_RESOLUTION_ACTOR) }
  }

  @Test
  fun `clusters beyond the first page are still inspected`() {
    val firstPage = (1..100).map { cluster("suggestionless-$it", 1) }
    val eligible = cluster("eligible", 1)
    activeSettings()
    every { dedupRepository.findUnresolvedClusters("library", 0, 100) } returns firstPage
    every { dedupRepository.findUnresolvedClusters("library", 100, 100) } returns listOf(eligible)
    every { resolutionRepository.hasResolutionAttempt(any(), any(), any(), any()) } returns false
    every { suggestionPlanner.evaluate(match<DedupClusterWithMembers> { it.cluster.id.startsWith("suggestionless-") }) } returns DedupSuggestion(null)
    every { suggestionPlanner.evaluate(eligible) } returns suggestion()
    every { resolutionLifecycle.createSuggested("eligible", 1, DEDUP_AUTO_RESOLUTION_ACTOR) } returns mockk<DedupResolution>()

    assertThat(lifecycle.submitBatch("library")).isEqualTo(DedupAutoResolutionBatchResult(1, 1, false))

    verify(exactly = 1) { dedupRepository.findUnresolvedClusters("library", 100, 100) }
    verify(exactly = 1) { resolutionLifecycle.createSuggested("eligible", 1, DEDUP_AUTO_RESOLUTION_ACTOR) }
  }

  @Test
  fun `submission failure before persistence is retried by the work lifecycle`() {
    val candidate = cluster("candidate", 1)
    activeSettings()
    every { dedupRepository.findUnresolvedClusters("library", 0, 100) } returns listOf(candidate)
    every { resolutionRepository.hasResolutionAttempt(any(), any(), any(), any()) } returns false
    every { suggestionPlanner.evaluate(candidate) } returns suggestion()
    every { resolutionLifecycle.createSuggested("candidate", 1, DEDUP_AUTO_RESOLUTION_ACTOR) } throws
      DedupResolutionExecutionException(null, "SUBMISSION_FAILED", false, "database unavailable")

    assertThatThrownBy { lifecycle.submitBatch("library") }
      .isInstanceOf(DedupResolutionExecutionException::class.java)
      .hasMessage("database unavailable")
  }

  private fun activeSettings() {
    every { dedupRepository.findLibrarySettings("library") } returns
      DedupLibrarySettings("library", enabled = true, autoResolveSuggestions = true)
  }

  private fun suggestion() = DedupSuggestion(DedupResolutionPlan("plan", emptyList()))

  private fun cluster(
    id: String,
    revision: Long,
    status: DedupClusterStatus = DedupClusterStatus.UNPROCESSED,
  ): DedupClusterWithMembers {
    val now = LocalDateTime.now()
    return DedupClusterWithMembers(
      DedupCluster(
        id,
        "library",
        revision,
        status,
        true,
        "book-$id",
        "topology-$revision",
        "evidence-$revision",
        "state-$revision",
        null,
        null,
        null,
        null,
        now,
        now,
        null,
      ),
      emptyList(),
    )
  }
}
