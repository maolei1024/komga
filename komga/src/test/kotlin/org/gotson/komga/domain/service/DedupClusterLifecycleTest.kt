package org.gotson.komga.domain.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupLocalStateSnapshot
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.DedupRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DedupClusterLifecycleTest {
  private val repository = mockk<DedupRepository>()
  private val cover = mockk<DedupCoverLifecycle>()
  private val localState = mockk<DedupLocalStateLifecycle>()
  private val lifecycle = DedupClusterLifecycle(repository, cover, localState)

  @Test
  fun `A-B and B-C form one connected cluster without treating A-C as direct evidence`() {
    val identities = listOf(identity("A"), identity("B"), identity("C"))
    every { repository.findLibrarySettings("library") } returns DedupLibrarySettings("library")
    every { repository.lockLibraryForClusterRebuild("library") } just Runs
    every { cover.currentSourceIdentities("library") } returns identities
    every { repository.findRelations("library") } returns listOf(relation("A", "B"), relation("B", "C"))
    every { repository.findAllClusters("library") } returns emptyList()
    every { localState.snapshot(any()) } answers { DedupLocalStateSnapshot(firstArg(), "state-${firstArg<String>()}", emptySet(), emptyMap()) }
    val clusterSlot = slot<DedupCluster>()
    val membersSlot = slot<Collection<DedupClusterMember>>()
    every { repository.saveCluster(capture(clusterSlot), capture(membersSlot)) } just Runs
    every { repository.markClusterSuperseded(any(), any(), any()) } just Runs

    assertThat(lifecycle.rebuildLibrary("library")).isEqualTo(1)
    assertThat(membersSlot.captured.map { it.bookId }).containsExactlyInAnyOrder("A", "B", "C")
    assertThat(clusterSlot.captured.status).isEqualTo(DedupClusterStatus.UNPROCESSED)
    assertThat(repository.findRelations("library")).hasSize(2)
  }

  @Test
  fun `unchanged fingerprints preserve stable id revision and timestamp`() {
    val now = LocalDateTime.of(2026, 8, 4, 12, 0)
    val identities = listOf(identity("A"), identity("B"))
    every { repository.findLibrarySettings("library") } returns DedupLibrarySettings("library")
    every { repository.lockLibraryForClusterRebuild("library") } just Runs
    every { cover.currentSourceIdentities("library") } returns identities
    every { repository.findRelations("library") } returns listOf(relation("A", "B"))
    every { localState.snapshot(any()) } answers { DedupLocalStateSnapshot(firstArg(), "state-${firstArg<String>()}", emptySet(), emptyMap()) }
    every { repository.findAllClusters("library") } returns emptyList()
    val clusters = mutableListOf<DedupCluster>()
    val memberLists = mutableListOf<Collection<DedupClusterMember>>()
    every { repository.saveCluster(capture(clusters), capture(memberLists)) } just Runs
    every { repository.markClusterSuperseded(any(), any(), any()) } just Runs

    lifecycle.rebuildLibrary("library", now)
    every { repository.findAllClusters("library") } returns listOf(DedupClusterWithMembers(clusters.first(), memberLists.first().toList()))
    lifecycle.rebuildLibrary("library", now.plusHours(1))

    assertThat(clusters.last().id).isEqualTo(clusters.first().id)
    assertThat(clusters.last().revision).isEqualTo(1)
    assertThat(clusters.last().lastModifiedDate).isEqualTo(now)
  }

  @Test
  fun `merge reuses the oldest cluster and supersedes every other overlap`() {
    val now = LocalDateTime.of(2026, 8, 4, 12, 0)
    val identities = listOf("A", "B", "C", "D").map(::identity)
    val oldest = storedCluster("oldest", listOf("A", "B"), "A", now.minusDays(2))
    val newer = storedCluster("newer", listOf("C", "D"), "C", now.minusDays(1))
    every { repository.findLibrarySettings("library") } returns DedupLibrarySettings("library")
    every { repository.lockLibraryForClusterRebuild("library") } just Runs
    every { cover.currentSourceIdentities("library") } returns identities
    every { repository.findRelations("library") } returns listOf(relation("A", "B"), relation("B", "C"), relation("C", "D"))
    every { repository.findAllClusters("library") } returns listOf(newer, oldest)
    every { localState.snapshot(any()) } answers { DedupLocalStateSnapshot(firstArg(), "state-${firstArg<String>()}", emptySet(), emptyMap()) }
    val saved = mutableListOf<DedupCluster>()
    every { repository.saveCluster(capture(saved), any()) } just Runs
    every { repository.markClusterSuperseded(any(), any(), any()) } just Runs

    lifecycle.rebuildLibrary("library", now)

    val merged = saved.single()
    assertThat(merged.id).isEqualTo("oldest")
    assertThat(merged.status).isEqualTo(DedupClusterStatus.UNPROCESSED)
    assertThat(merged.reopenReason).isEqualTo("CLUSTERS_MERGED")
    io.mockk.verify(exactly = 1) { repository.markClusterSuperseded("newer", "oldest", now) }
  }

  @Test
  fun `split keeps the anchor component id and marks every child as split`() {
    val now = LocalDateTime.of(2026, 8, 4, 12, 0)
    val identities = listOf("A", "B", "C", "D").map(::identity)
    val old = storedCluster("original", listOf("A", "B", "C", "D"), "A", now.minusDays(1))
    every { repository.findLibrarySettings("library") } returns DedupLibrarySettings("library")
    every { repository.lockLibraryForClusterRebuild("library") } just Runs
    every { cover.currentSourceIdentities("library") } returns identities
    every { repository.findRelations("library") } returns listOf(relation("A", "B"), relation("C", "D"))
    every { repository.findAllClusters("library") } returns listOf(old)
    every { localState.snapshot(any()) } answers { DedupLocalStateSnapshot(firstArg(), "state-${firstArg<String>()}", emptySet(), emptyMap()) }
    val saved = mutableListOf<DedupCluster>()
    val members = mutableListOf<Collection<DedupClusterMember>>()
    every { repository.saveCluster(capture(saved), capture(members)) } just Runs
    every { repository.markClusterSuperseded(any(), any(), any()) } just Runs

    lifecycle.rebuildLibrary("library", now)

    assertThat(saved).hasSize(2).allMatch { it.status == DedupClusterStatus.UNPROCESSED && it.reopenReason == "CLUSTER_SPLIT" }
    val anchorIndex = saved.indexOfFirst { it.id == "original" }
    assertThat(anchorIndex).isNotNegative()
    assertThat(members[anchorIndex].map { it.bookId }).containsExactlyInAnyOrder("A", "B")
    assertThat(saved.single { it.id != "original" }.id).startsWith("cluster-")
  }

  @Test
  fun `a dormant processed cluster reuses its id when a new candidate joins the keeper`() {
    val now = LocalDateTime.of(2026, 8, 4, 12, 0)
    val dormant = storedCluster("dormant", listOf("A"), "A", now.minusDays(1), DedupClusterStatus.PROCESSED)
    every { repository.findLibrarySettings("library") } returns DedupLibrarySettings("library")
    every { repository.lockLibraryForClusterRebuild("library") } just Runs
    every { cover.currentSourceIdentities("library") } returns listOf(identity("A"), identity("B"))
    every { repository.findRelations("library") } returns listOf(relation("A", "B"))
    every { repository.findAllClusters("library") } returns listOf(dormant)
    every { localState.snapshot(any()) } answers { DedupLocalStateSnapshot(firstArg(), "state-${firstArg<String>()}", emptySet(), emptyMap()) }
    val saved = slot<DedupCluster>()
    every { repository.saveCluster(capture(saved), any()) } just Runs
    every { repository.markClusterSuperseded(any(), any(), any()) } just Runs

    lifecycle.rebuildLibrary("library", now)

    assertThat(saved.captured.id).isEqualTo("dormant")
    assertThat(saved.captured.reviewable).isTrue()
    assertThat(saved.captured.status).isEqualTo(DedupClusterStatus.UNPROCESSED)
  }

  @Test
  fun `finalized survivor fingerprint does not reopen on the next rebuild`() {
    val now = LocalDateTime.of(2026, 8, 4, 12, 0)
    val processing = storedCluster("cluster", listOf("A", "B"), "A", now.minusDays(1), DedupClusterStatus.PROCESSING)
    every { repository.findCluster("cluster") } returns processing
    every { repository.findLibrarySettings("library") } returns DedupLibrarySettings("library")
    every { repository.findRelationsForBooks(setOf("A")) } returns emptyList()
    every { cover.currentSourceIdentity("A") } returns identity("A")
    every { localState.snapshot("A") } returns DedupLocalStateSnapshot("A", "state-A", emptySet(), emptyMap())
    val saved = mutableListOf<DedupCluster>()
    val members = mutableListOf<Collection<DedupClusterMember>>()
    every { repository.saveCluster(capture(saved), capture(members)) } just Runs

    val finalized = lifecycle.finalizeProcessed("cluster", "resolution", setOf("A"), now)
    every { repository.lockLibraryForClusterRebuild("library") } just Runs
    every { cover.currentSourceIdentities("library") } returns listOf(identity("A"))
    every { repository.findRelations("library") } returns emptyList()
    every { repository.findAllClusters("library") } returns listOf(DedupClusterWithMembers(finalized, members.first().toList()))
    every { repository.markClusterSuperseded(any(), any(), any()) } just Runs

    lifecycle.rebuildLibrary("library", now.plusHours(1))

    assertThat(saved.last().status).isEqualTo(DedupClusterStatus.PROCESSED)
    assertThat(saved.last().revision).isEqualTo(finalized.revision)
    assertThat(saved.last().processedRevision).isEqualTo(finalized.processedRevision)
  }

  private fun identity(id: String) = DedupSourceIdentity(id, "series-$id", "library", "content-$id", "cover-$id", "metadata-$id", "scope-$id", 10)

  private fun storedCluster(
    id: String,
    ids: List<String>,
    anchor: String,
    created: LocalDateTime,
    status: DedupClusterStatus = DedupClusterStatus.PROCESSED,
  ): DedupClusterWithMembers {
    val cluster = DedupCluster(id, "library", 3, status, ids.size >= 2, anchor, "old-topology", "old-evidence", "old-state", 3, "resolution", null, null, created, created, created)
    return DedupClusterWithMembers(cluster, ids.map { DedupClusterMember(id, it, true, "content-$it", "cover-$it", "metadata-$it", "scope-$it", created, created) })
  }

  private fun relation(
    left: String,
    right: String,
  ) = DedupRelation(
    id = "relation-$left-$right",
    libraryId = "library",
    bookLowId = left,
    bookHighId = right,
    lowContentGeneration = "content-$left",
    highContentGeneration = "content-$right",
    type = DedupRelationType.EXACT_FILE,
  )
}
