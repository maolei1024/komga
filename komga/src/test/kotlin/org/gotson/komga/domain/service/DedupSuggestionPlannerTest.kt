package org.gotson.komga.domain.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupLocalStateSnapshot
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URL
import java.time.LocalDateTime

class DedupSuggestionPlannerTest {
  private val repository = mockk<DedupRepository>()
  private val resolutions = mockk<DedupResolutionRepository>()
  private val books = mockk<BookRepository>()
  private val cover = mockk<DedupCoverLifecycle>()
  private val localState = mockk<DedupLocalStateLifecycle>()
  private val deletion = mockk<DedupPhysicalBookDeletionLifecycle>()
  private val clusters = mockk<DedupClusterLifecycle>()
  private val planner = DedupSuggestionPlanner(repository, resolutions, books, cover, localState, deletion, clusters)

  @BeforeEach
  fun defaults() {
    every { resolutions.hasActiveResolutionForBooks(any()) } returns false
    every { books.findByIdOrNull(any()) } answers { book(firstArg()) }
    every { deletion.precheck(any()) } returns DedupFilePrecheck(DedupFilePrecheckStatus.AVAILABLE, "/tmp/book.cbz", 10, 10)
    every { localState.snapshot(any()) } answers { DedupLocalStateSnapshot(firstArg(), "state-${firstArg<String>()}", emptySet(), emptyMap()) }
    every { clusters.currentFingerprints(any()) } returns ClusterFingerprints("topology", "evidence", "state")
  }

  @Test
  fun `two directly safe subgroups produce multiple keepers and every delete keeps direct evidence`() {
    val value = cluster("A", "B", "C", "D")
    identities("A", "B", "C", "D")
    val relations = listOf(exact("A", "B"), exact("C", "D"))
    every { repository.findRelationsForBooks(any()) } returns relations

    val plan = planner.evaluate(value).plan!!

    assertThat(plan.keepCount).isEqualTo(2)
    assertThat(plan.deleteCount).isEqualTo(2)
    plan.members.filter { it.action == DedupResolutionAction.DELETE }.forEach { member ->
      assertThat(relations).anyMatch { it.id == member.directRelationId && setOf(it.bookLowId, it.bookHighId) == setOf(member.bookId, member.keeperBookId) }
    }
  }

  @Test
  fun `local reading state wins an exact pair keeper tie`() {
    val value = cluster("A", "B")
    identities("A", "B")
    every { repository.findRelationsForBooks(any()) } returns listOf(exact("A", "B"))
    every { localState.snapshot("B") } returns DedupLocalStateSnapshot("B", "state-B", setOf("READ_PROGRESS_PRESENT"), emptyMap())

    val plan = planner.evaluate(value).plan!!

    assertThat(plan.members.single { it.bookId == "B" }.action).isEqualTo(DedupResolutionAction.KEEP)
    assertThat(plan.members.single { it.bookId == "A" }.keeperBookId).isEqualTo("B")
  }

  @Test
  fun `contained relation can only remove the subset`() {
    val value = cluster("A", "B")
    identities("A", "B")
    every { repository.findRelationsForBooks(any()) } returns listOf(exact("A", "B").copy(type = DedupRelationType.CONTAINED_IN, containedBookId = "A", containerBookId = "B", classifierRuleVersion = 2))

    val plan = planner.evaluate(value).plan!!

    assertThat(plan.members.single { it.action == DedupResolutionAction.DELETE }.bookId).isEqualTo("A")
    assertThat(plan.members.single { it.bookId == "A" }.keeperBookId).isEqualTo("B")
  }

  private fun cluster(vararg ids: String): DedupClusterWithMembers {
    val now = LocalDateTime.now()
    val cluster = DedupCluster("cluster", "library", 1, DedupClusterStatus.UNPROCESSED, true, ids.first(), "topology", "evidence", "state", null, null, null, null, now, now, null)
    return DedupClusterWithMembers(cluster, ids.map { DedupClusterMember("cluster", it, true, "content-$it", "cover-$it", "metadata-$it", "scope-$it", now, now) })
  }

  private fun identities(vararg ids: String) {
    ids.forEach { id -> every { cover.currentSourceIdentity(id) } returns identity(id) }
  }

  private fun identity(id: String) =
    DedupSourceIdentity(
      id,
      "series-$id",
      "library",
      "content-$id",
      "cover-$id",
      "metadata-$id",
      "scope-$id",
      10,
      org.gotson.komga.domain.model.DedupArchiveHashState.READY,
      "hash-$id",
    )

  private fun exact(
    left: String,
    right: String,
  ) = DedupRelation("relation-$left-$right", "library", left, right, "content-$left", "content-$right", type = DedupRelationType.EXACT_FILE)

  private fun book(id: String) = Book(id, URL("file:/tmp/$id.cbz"), LocalDateTime.MIN, 10, id = id, seriesId = "series-$id", libraryId = "library")
}
