package org.gotson.komga.domain.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URL
import java.time.LocalDateTime

class DedupSuggestionPlannerTest {
  private val repository = mockk<DedupRepository>()
  private val books = mockk<BookRepository>()
  private val cover = mockk<DedupCoverLifecycle>()
  private val clusters = mockk<DedupClusterLifecycle>()
  private val planner = DedupSuggestionPlanner(repository, books, cover, clusters)

  @BeforeEach
  fun defaults() {
    every { repository.findPageFeatures(any(), any(), any()) } returns emptyList()
  }

  @Test
  fun `contained 1 to 5 sequence keeps the 1 to 20 container`() {
    setup(listOf(identity("short", 5), identity("long", 20)), listOf(relation("short", "long", DedupRelationType.CONTAINED_IN).copy(containedBookId = "short", containerBookId = "long")))

    val plan = planner.evaluate(cluster("short", "long")).plan!!

    assertThat(plan.members.single { it.action == DedupResolutionAction.KEEP }.bookId).isEqualTo("long")
    assertThat(plan.members.single { it.action == DedupResolutionAction.DELETE }.bookId).isEqualTo("short")
  }

  @Test
  fun `similar titles without verified containment do not produce a suggestion`() {
    setup(listOf(identity("A", 5), identity("B", 20)), emptyList(), names = mapOf("A" to "Story 1-5", "B" to "Story 1-20"))

    assertThat(planner.evaluate(cluster("A", "B")).plan).isNull()
  }

  @Test
  fun `exact page duplicates choose the unique higher bytes per page Book`() {
    setup(
      listOf(identity("A", 10), identity("B", 10)),
      listOf(relation("A", "B", DedupRelationType.EXACT_PAGE_SEQUENCE)),
      sizes = mapOf("A" to 1_000L, "B" to 2_000L),
    )

    val plan = planner.evaluate(cluster("A", "B")).plan!!

    assertThat(plan.members.single { it.action == DedupResolutionAction.KEEP }.bookId).isEqualTo("B")
  }

  @Test
  fun `semantic quality tie keeps the Book created first`() {
    val earlier = LocalDateTime.of(2025, 1, 1, 0, 0)
    val later = earlier.plusDays(1)
    setup(
      listOf(identity("A", 10), identity("B", 10)),
      listOf(relation("A", "B", DedupRelationType.EXACT_PAGE_SEQUENCE)),
      sizes = mapOf("A" to 1_000L, "B" to 1_000L),
      createdDates = mapOf("A" to later, "B" to earlier),
    )

    val plan = planner.evaluate(cluster("A", "B")).plan!!

    assertThat(plan.members.single { it.action == DedupResolutionAction.KEEP }.bookId).isEqualTo("B")
  }

  @Test
  fun `creation time tie keeps the lexicographically smallest Book ID deterministically`() {
    setup(
      listOf(identity("B", 10), identity("A", 10)),
      listOf(relation("A", "B", DedupRelationType.EXACT_PAGE_SEQUENCE)),
      sizes = mapOf("A" to 1_000L, "B" to 1_000L),
    )

    val plans = List(3) { planner.evaluate(cluster("B", "A")).plan!! }

    assertThat(plans.map { plan -> plan.members.single { it.action == DedupResolutionAction.KEEP }.bookId }).containsOnly("A")
    assertThat(plans.map { it.revision }).containsOnly(plans.first().revision)
  }

  @Test
  fun `multi Book quality tie keeps the unique oldest safe candidate`() {
    val earlier = LocalDateTime.of(2025, 1, 1, 0, 0)
    val later = earlier.plusDays(1)
    setup(
      listOf(identity("A", 10), identity("B", 10), identity("C", 10)),
      listOf(
        relation("A", "B", DedupRelationType.EXACT_PAGE_SEQUENCE),
        relation("A", "C", DedupRelationType.EXACT_PAGE_SEQUENCE),
        relation("B", "C", DedupRelationType.EXACT_PAGE_SEQUENCE),
      ),
      sizes = mapOf("A" to 1_000L, "B" to 1_000L, "C" to 1_000L),
      createdDates = mapOf("A" to later, "B" to earlier, "C" to later),
    )

    val plan = planner.evaluate(cluster("A", "B", "C")).plan!!

    assertThat(plan.members.single { it.action == DedupResolutionAction.KEEP }.bookId).isEqualTo("B")
    assertThat(plan.members.count { it.action == DedupResolutionAction.DELETE }).isEqualTo(2)
  }

  @Test
  fun `risk relation types never create automatic deletion suggestions`() {
    setup(listOf(identity("A", 10), identity("B", 10)), listOf(relation("A", "B", DedupRelationType.PARTIAL_OVERLAP)))

    assertThat(planner.evaluate(cluster("A", "B")).plan).isNull()
  }

  private fun setup(
    identities: List<DedupSourceIdentity>,
    relations: List<DedupRelation>,
    sizes: Map<String, Long> = emptyMap(),
    names: Map<String, String> = emptyMap(),
    createdDates: Map<String, LocalDateTime> = emptyMap(),
  ) {
    identities.forEach { identity ->
      every { cover.currentSourceIdentity(identity.bookId) } returns identity
      every { books.findByIdOrNull(identity.bookId) } returns
        Book(
          name = names[identity.bookId] ?: identity.bookId,
          url = URL("file:/tmp/${identity.bookId}.cbz"),
          fileLastModified = LocalDateTime.MIN,
          fileSize = sizes[identity.bookId] ?: 1_000,
          id = identity.bookId,
          seriesId = identity.seriesId,
          libraryId = identity.libraryId,
          createdDate = createdDates[identity.bookId] ?: LocalDateTime.of(2025, 1, 1, 0, 0),
        )
    }
    every { clusters.currentReviewRelationsForIdentities(any()) } returns relations
  }

  private fun cluster(vararg ids: String): DedupClusterWithMembers {
    val now = LocalDateTime.now()
    val value = DedupCluster("cluster", "library", 1, DedupClusterStatus.UNPROCESSED, true, ids.first(), "topology", "evidence", "state", null, null, null, null, now, now, null)
    return DedupClusterWithMembers(value, ids.map { DedupClusterMember("cluster", it, true, "content-$it", "cover-$it", "metadata-$it", "scope-$it", now, now) })
  }

  private fun identity(
    id: String,
    pages: Int,
  ) = DedupSourceIdentity(id, "series-$id", "library", "content-$id", "cover-$id", "metadata-$id", "scope-$id", pages)

  private fun relation(
    first: String,
    second: String,
    type: DedupRelationType,
  ): DedupRelation {
    val low = minOf(first, second)
    val high = maxOf(first, second)
    return DedupRelation(
      "relation-$low-$high",
      "library",
      low,
      high,
      "content-$low",
      "content-$high",
      type = type,
      featureSchemaVersion = DedupDeepVerificationLifecycle.PAGE_FEATURE_SCHEMA_VERSION,
      classifierRuleVersion = DedupDeepVerificationLifecycle.CLASSIFIER_RULE_VERSION,
    )
  }
}
