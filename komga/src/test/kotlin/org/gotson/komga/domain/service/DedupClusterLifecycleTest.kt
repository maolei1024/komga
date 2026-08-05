package org.gotson.komga.domain.service

import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupPairDecision
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationStatus
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.DedupRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DedupClusterLifecycleTest {
  private val repository = mockk<DedupRepository>()
  private val cover = mockk<DedupCoverLifecycle>()
  private val lifecycle = DedupClusterLifecycle(repository, cover)

  @BeforeEach
  fun clearInteractions() {
    clearMocks(repository, cover)
  }

  @Test
  fun `verified supported edges form one current unresolved component`() {
    defaults(listOf(identity("A"), identity("B"), identity("C")), listOf(relation("A", "B"), relation("B", "C")))
    val members = slot<Collection<DedupClusterMember>>()
    every { repository.saveCluster(any(), capture(members)) } just Runs

    assertThat(lifecycle.rebuildLibrary("library")).isEqualTo(1)
    assertThat(members.captured.map { it.bookId }).containsExactlyInAnyOrder("A", "B", "C")
  }

  @Test
  fun `cover candidates and unrelated verification never create review clusters`() {
    val candidate = relation("A", "B").copy(type = DedupRelationType.VISUALLY_SIMILAR, status = DedupRelationStatus.CANDIDATE)
    defaults(listOf(identity("A"), identity("B")), listOf(candidate))
    assertThat(lifecycle.rebuildLibrary("library")).isZero()
    verify(exactly = 0) { repository.saveCluster(any(), any()) }

    every { repository.findRelations("library") } returns listOf(relation("A", "B").copy(type = DedupRelationType.UNRELATED))
    assertThat(lifecycle.rebuildLibrary("library")).isZero()
    verify(exactly = 0) { repository.saveCluster(any(), any()) }
  }

  @Test
  fun `verified deep edge with stale metadata generation never creates a review cluster`() {
    val stale = relation("A", "B").copy(type = DedupRelationType.ALT_EDITION)
    defaults(listOf(identity("A"), identity("B").copy(metadataGeneration = "new-metadata-B")), listOf(stale))

    assertThat(lifecycle.rebuildLibrary("library")).isZero()
    verify(exactly = 0) { repository.saveCluster(any(), any()) }
  }

  @Test
  fun `KEEP_BOTH suppresses the canonical pair across Book generation changes`() {
    val changed = listOf(identity("A").copy(contentGeneration = "new-A"), identity("B").copy(metadataGeneration = "new-B"))
    val current = relation("A", "B").copy(lowContentGeneration = "new-A")
    defaults(changed, listOf(current), decisions = listOf(DedupPairDecision("A", "B", resolutionId = "resolution", actorId = "admin")))

    assertThat(lifecycle.rebuildLibrary("library")).isZero()
    verify(exactly = 0) { repository.saveCluster(any(), any()) }
  }

  @Test
  fun `new edge beside an old survivor creates a new cluster without rewriting processed history`() {
    val now = LocalDateTime.now()
    val processed = storedCluster("processed", listOf("A", "B"), DedupClusterStatus.PROCESSED, now.minusDays(1))
    defaults(listOf(identity("A"), identity("C")), listOf(relation("A", "C")), existing = listOf(processed))
    val saved = slot<DedupCluster>()
    every { repository.saveCluster(capture(saved), any()) } just Runs

    lifecycle.rebuildLibrary("library", now)

    assertThat(saved.captured.id).isNotEqualTo("processed")
    assertThat(saved.captured.status).isEqualTo(DedupClusterStatus.UNPROCESSED)
    verify(exactly = 0) { repository.saveCluster(match { it.id == "processed" }, any()) }
  }

  @Test
  fun `a component with one active member leaves the pending projection`() {
    val now = LocalDateTime.now()
    val old = storedCluster("cluster", listOf("A", "B"), DedupClusterStatus.UNPROCESSED, now.minusDays(1))
    defaults(listOf(identity("A")), emptyList(), existing = listOf(old))
    val saved = slot<DedupCluster>()
    every { repository.saveCluster(capture(saved), any()) } just Runs

    lifecycle.rebuildLibrary("library", now)

    assertThat(saved.captured.reviewable).isFalse()
    assertThat(saved.captured.memberCount).isEqualTo(1)
  }

  @Test
  fun `successful finalization writes survivor KEEP_BOTH decisions before rebuilding`() {
    val now = LocalDateTime.now()
    var stored = storedCluster("cluster", listOf("A", "B"), DedupClusterStatus.PROCESSING, now.minusMinutes(1))
    val decisions = mutableListOf<DedupPairDecision>()
    every { repository.findCluster("cluster") } answers { stored }
    every { cover.currentSourceIdentity("A") } returns identity("A")
    every { cover.currentSourceIdentity("B") } returns identity("B")
    every { cover.currentSourceIdentities("library") } returns listOf(identity("A"), identity("B"))
    every { repository.findRelations("library") } returns listOf(relation("A", "B"))
    every { repository.findPairDecisions("library") } answers { decisions.toList() }
    every { repository.savePairDecisions(any()) } answers { decisions += firstArg<Collection<DedupPairDecision>>() }
    every { repository.saveCluster(any(), any()) } answers { stored = DedupClusterWithMembers(firstArg(), secondArg<Collection<DedupClusterMember>>().toList()) }
    every { repository.findLibrarySettings("library") } returns DedupLibrarySettings("library")
    every { repository.lockLibraryForClusterRebuild("library") } just Runs
    every { repository.findAllClusters("library") } answers { listOf(stored) }
    every { repository.markClusterSuperseded(any(), any(), any()) } just Runs

    val result = lifecycle.finalizeProcessed("cluster", "resolution", "admin", setOf("A", "B"), now)

    assertThat(result.status).isEqualTo(DedupClusterStatus.PROCESSED)
    val decision = decisions.single()
    assertThat(decision.bookLowId to decision.bookHighId).isEqualTo("A" to "B")
    assertThat(decision.resolutionId).isEqualTo("resolution")
    assertThat(decision.actorId).isEqualTo("admin")
  }

  private fun defaults(
    identities: List<DedupSourceIdentity>,
    relations: List<DedupRelation>,
    decisions: List<DedupPairDecision> = emptyList(),
    existing: List<DedupClusterWithMembers> = emptyList(),
  ) {
    every { repository.findLibrarySettings("library") } returns DedupLibrarySettings("library")
    every { repository.lockLibraryForClusterRebuild("library") } just Runs
    every { cover.currentSourceIdentities("library") } returns identities
    every { repository.findRelations("library") } returns relations
    every { repository.findPairDecisions("library") } returns decisions
    every { repository.findAllClusters("library") } returns existing
    every { repository.markClusterSuperseded(any(), any(), any()) } just Runs
  }

  private fun identity(id: String) = DedupSourceIdentity(id, "series-$id", "library", "content-$id", "cover-$id", "metadata-$id", "scope-$id", 10)

  private fun relation(
    first: String,
    second: String,
  ): DedupRelation {
    val low = minOf(first, second)
    val high = maxOf(first, second)
    return DedupRelation(
      id = "relation-$low-$high",
      libraryId = "library",
      bookLowId = low,
      bookHighId = high,
      lowContentGeneration = "content-$low",
      highContentGeneration = "content-$high",
      lowCoverGeneration = "cover-$low",
      highCoverGeneration = "cover-$high",
      lowMetadataGeneration = "metadata-$low",
      highMetadataGeneration = "metadata-$high",
      type = DedupRelationType.EXACT_FILE,
    )
  }

  private fun storedCluster(
    id: String,
    ids: List<String>,
    status: DedupClusterStatus,
    now: LocalDateTime,
  ): DedupClusterWithMembers {
    val cluster =
      DedupCluster(
        id,
        "library",
        1,
        status,
        ids.size >= 2,
        ids.first(),
        "topology",
        "evidence",
        "state",
        if (status == DedupClusterStatus.PROCESSED) 1 else null,
        if (status == DedupClusterStatus.PROCESSED) "resolution" else null,
        null,
        null,
        now,
        now,
        if (status == DedupClusterStatus.PROCESSED) now else null,
      )
    return DedupClusterWithMembers(cluster, ids.map { DedupClusterMember(id, it, true, "content-$it", "cover-$it", "metadata-$it", "scope-$it", now, now) })
  }
}
