package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupEvidenceMaturity
import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupFeatureState
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupPairDecision
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionMember
import org.gotson.komga.domain.model.DedupResolutionMemberState
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.service.DedupCoverLifecycle
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DedupDaoTest(
  @Autowired private val dao: DedupDao,
  @Autowired private val exactDuplicateBookDao: ExactDuplicateBookDao,
  @Autowired private val libraryDao: LibraryDao,
  @Autowired private val seriesDao: SeriesDao,
  @Autowired private val bookDao: BookDao,
) {
  private lateinit var library: Library

  @BeforeEach
  fun setup() {
    library = makeLibrary("dedup-${UUID.randomUUID()}")
    libraryDao.insert(library)
  }

  @Test
  fun `new library settings round trip without completion stability`() {
    val value =
      DedupLibrarySettings(
        library.id,
        enabled = true,
        paused = true,
        batchSize = 25,
        maxDurationSeconds = 45,
        quietPeriodSeconds = 120,
        autoResolveSuggestions = true,
      )
    dao.saveLibrarySettings(value)
    assertThat(dao.findLibrarySettings(library.id)).usingRecursiveComparison().ignoringFields("createdDate", "lastModifiedDate").isEqualTo(value)
  }

  @Test
  fun `unscanned CBZ query applies a stable DAO-level limit`() {
    val series = makeSeries("series-${UUID.randomUUID()}", library.id)
    seriesDao.insert(series)
    val now = LocalDateTime.now()
    val books =
      listOf(
        org.gotson.komga.domain.model
          .Book("A", URL("file:/A.cbz"), now, 100, id = "A-${library.id}", seriesId = series.id, libraryId = library.id, createdDate = now, lastModifiedDate = now),
        org.gotson.komga.domain.model
          .Book("B", URL("file:/B.cbz"), now, 100, id = "B-${library.id}", seriesId = series.id, libraryId = library.id, createdDate = now.plusSeconds(1), lastModifiedDate = now.plusSeconds(1)),
        org.gotson.komga.domain.model
          .Book("C", URL("file:/C.cbz"), now, 100, id = "C-${library.id}", seriesId = series.id, libraryId = library.id, createdDate = now.plusSeconds(2), lastModifiedDate = now.plusSeconds(2)),
        org.gotson.komga.domain.model
          .Book("PDF", URL("file:/D.pdf"), now, 100, id = "D-${library.id}", seriesId = series.id, libraryId = library.id, createdDate = now.plusSeconds(3), lastModifiedDate = now.plusSeconds(3)),
      )
    bookDao.insert(books)
    dao.saveFeature(
      DedupFeature(
        bookId = books[0].id,
        seriesId = series.id,
        libraryId = library.id,
        sourceContentGeneration = "content",
        sourceCoverGeneration = "cover",
        sourceMetadataGeneration = "metadata",
        seriesScopeRevision = "scope",
        featureSchemaVersion = DedupCoverLifecycle.FEATURE_SCHEMA_VERSION,
        coverState = DedupFeatureState.WAITING,
        coverSource = null,
        coverHash = null,
        coverQuality = null,
        pageCount = null,
        analyzedDate = now,
        lastModifiedDate = now,
      ),
    )

    assertThat(dao.findUnscannedBookIds(library.id, DedupCoverLifecycle.FEATURE_SCHEMA_VERSION, 1)).containsExactly(books[1].id)
    assertThat(dao.findUnscannedBookIds(library.id, DedupCoverLifecycle.FEATURE_SCHEMA_VERSION, 10)).containsExactly(books[1].id, books[2].id)

    assertThat(dao.countPendingScanBooks(setOf(library.id), DedupCoverLifecycle.FEATURE_SCHEMA_VERSION)).isEqualTo(2)
    dao.enqueueWork("scan-current-${UUID.randomUUID()}", library.id, DedupWorkType.SCAN_BOOK, books[0].id)
    dao.enqueueWork("scan-unscanned-${UUID.randomUUID()}", library.id, DedupWorkType.SCAN_BOOK, books[1].id)
    assertThat(dao.countPendingScanBooks(setOf(library.id), DedupCoverLifecycle.FEATURE_SCHEMA_VERSION)).isEqualTo(3)
    assertThat(dao.countPendingScanBooks(emptySet(), DedupCoverLifecycle.FEATURE_SCHEMA_VERSION)).isZero()
  }

  @Test
  fun `Book-scoped exact duplicate lookup never crosses Library boundaries`() {
    val otherLibrary = makeLibrary("dedup-other-${UUID.randomUUID()}")
    libraryDao.insert(otherLibrary)
    val series = makeSeries("series-${UUID.randomUUID()}", library.id)
    val otherSeries = makeSeries("series-${UUID.randomUUID()}", otherLibrary.id)
    seriesDao.insert(series)
    seriesDao.insert(otherSeries)
    val target = makeBook("target.cbz", libraryId = library.id, seriesId = series.id).copy(fileHash = "same-hash", fileSize = 100)
    val sameLibrary = makeBook("same.cbz", libraryId = library.id, seriesId = series.id).copy(fileHash = "same-hash", fileSize = 100)
    val other = makeBook("other.cbz", libraryId = otherLibrary.id, seriesId = otherSeries.id).copy(fileHash = "same-hash", fileSize = 100)
    bookDao.insert(listOf(target, sameLibrary, other))

    assertThat(exactDuplicateBookDao.findExactDuplicatesForBook(target.id).map { it.id })
      .containsExactlyInAnyOrder(target.id, sameLibrary.id)
  }

  @Test
  fun `KEEP_BOTH decisions use canonical unique pairs and cascade with Book deletion`() {
    val series = makeSeries("series-${UUID.randomUUID()}", library.id)
    seriesDao.insert(series)
    val low = makeBook("low.cbz", libraryId = library.id, seriesId = series.id, id = "A-${UUID.randomUUID()}")
    val high = makeBook("high.cbz", libraryId = library.id, seriesId = series.id, id = "B-${UUID.randomUUID()}")
    bookDao.insert(listOf(low, high))
    val decision = DedupPairDecision(low.id, high.id, resolutionId = null, actorId = "admin")

    dao.savePairDecisions(listOf(decision))
    dao.savePairDecisions(listOf(decision.copy(actorId = "other")))

    assertThat(dao.findPairDecisions(library.id)).singleElement().extracting("actorId").isEqualTo("other")
    bookDao.delete(low.id)
    assertThat(dao.findPairDecisions(library.id)).isEmpty()
  }

  @Test
  fun `touching relation lookup covers both endpoints without crossing Library boundaries`() {
    val otherLibrary = makeLibrary("dedup-other-${UUID.randomUUID()}")
    libraryDao.insert(otherLibrary)
    val series = makeSeries("series-${UUID.randomUUID()}", library.id)
    val otherSeries = makeSeries("series-${UUID.randomUUID()}", otherLibrary.id)
    seriesDao.insert(series)
    seriesDao.insert(otherSeries)
    val suffix = UUID.randomUUID().toString()
    val low = makeBook("low.cbz", libraryId = library.id, seriesId = series.id, id = "A-$suffix")
    val target = makeBook("target.cbz", libraryId = library.id, seriesId = series.id, id = "M-$suffix")
    val high = makeBook("high.cbz", libraryId = library.id, seriesId = series.id, id = "Z-$suffix")
    val otherLow = makeBook("other-low.cbz", libraryId = otherLibrary.id, seriesId = otherSeries.id, id = "A-other-$suffix")
    val otherTarget = makeBook("other-target.cbz", libraryId = otherLibrary.id, seriesId = otherSeries.id, id = "M-other-$suffix")
    bookDao.insert(listOf(low, target, high, otherLow, otherTarget))
    dao.saveRelation(relation(library.id, low.id, target.id))
    dao.saveRelation(relation(library.id, target.id, high.id))
    dao.saveRelation(relation(library.id, low.id, high.id))
    dao.saveRelation(relation(otherLibrary.id, otherLow.id, otherTarget.id))

    assertThat(dao.findRelationsTouchingBooks(library.id, setOf(target.id, otherTarget.id)).map { it.bookLowId to it.bookHighId })
      .containsExactly(low.id to target.id, target.id to high.id)
    assertThat(dao.findRelationsTouchingBooks(library.id, emptySet())).isEmpty()
  }

  @Test
  fun `coalesced work keeps a newer revision pending after an older lease completes`() {
    val now = LocalDateTime.of(2026, 8, 4, 12, 0)
    dao.enqueueWork("work-${UUID.randomUUID()}", library.id, DedupWorkType.REBUILD_CLUSTERS, notBefore = now)
    val claimed = dao.claimNextWork("worker", Duration.ofMinutes(2), libraryId = library.id, now = now)!!
    dao.enqueueWork("ignored", library.id, DedupWorkType.REBUILD_CLUSTERS, notBefore = now)

    assertThat(dao.completeWork(claimed.id, claimed.leaseToken!!, claimed.desiredRevision, now)).isTrue()
    assertThat(dao.findWorkById(claimed.id))
      .extracting("state", "desiredRevision", "completedRevision")
      .containsExactly(DedupWorkState.PENDING, 2L, 1L)
  }

  @Test
  fun `cluster revision and state fingerprint are claimed with compare and set`() {
    val now = LocalDateTime.now()
    val cluster =
      DedupCluster(
        "cluster-${UUID.randomUUID()}",
        library.id,
        3,
        DedupClusterStatus.UNPROCESSED,
        true,
        "book-A",
        "topology",
        "evidence",
        "state",
        null,
        null,
        null,
        null,
        now,
        now,
        null,
      )
    dao.saveCluster(cluster, emptyList())

    assertThat(dao.claimCluster(cluster.id, 2, "state")).isFalse()
    assertThat(dao.claimCluster(cluster.id, 3, "wrong")).isFalse()
    assertThat(dao.claimCluster(cluster.id, 3, "state")).isTrue()
    assertThat(dao.findCluster(cluster.id)?.cluster?.status).isEqualTo(DedupClusterStatus.PROCESSING)
  }

  @Test
  fun `cluster members round trip and removed members remain as non-present history`() {
    val now = LocalDateTime.now()
    val series = makeSeries("series", library.id)
    seriesDao.insert(series)
    val books = listOf("A", "B").map { makeBook(it, libraryId = library.id, seriesId = series.id) }
    books.forEach(bookDao::insert)
    val cluster =
      DedupCluster(
        "cluster-${UUID.randomUUID()}",
        library.id,
        1,
        DedupClusterStatus.UNPROCESSED,
        true,
        books[0].id,
        "topology",
        "evidence",
        "state",
        null,
        null,
        null,
        null,
        now,
        now,
        null,
      )
    val members =
      books.map {
        DedupClusterMember(cluster.id, it.id, true, "content-${it.id}", "cover-${it.id}", "metadata-${it.id}", "scope-${it.id}", now, now)
      }

    dao.saveCluster(cluster, members)
    dao.saveCluster(cluster.copy(revision = 2, reviewable = false, lastModifiedDate = now.plusSeconds(1)), listOf(members[0]))

    val stored = dao.findCluster(cluster.id)!!
    assertThat(stored.cluster.revision).isEqualTo(2)
    assertThat(stored.members).hasSize(2)
    assertThat(stored.members.single { it.bookId == books[0].id }.present).isTrue()
    assertThat(stored.members.single { it.bookId == books[1].id }.present).isFalse()
  }

  @Test
  fun `cluster evidence filtering and pagination use persisted summary fields`() {
    val now = LocalDateTime.now()
    val coverOnly =
      DedupCluster(
        "cluster-cover-${UUID.randomUUID()}",
        library.id,
        1,
        DedupClusterStatus.UNPROCESSED,
        true,
        "A",
        "topology-a",
        "evidence-a",
        "state-a",
        null,
        null,
        null,
        null,
        now,
        now.minusMinutes(1),
        null,
        2,
        0,
        1,
        DedupEvidenceMaturity.COVER_ONLY,
      )
    val complete =
      DedupCluster(
        "cluster-complete-${UUID.randomUUID()}",
        library.id,
        1,
        DedupClusterStatus.UNPROCESSED,
        true,
        "B",
        "topology-b",
        "evidence-b",
        "state-b",
        null,
        null,
        null,
        null,
        now,
        now,
        null,
        2,
        1,
        1,
        DedupEvidenceMaturity.COMPLETE,
      )
    dao.saveCluster(coverOnly, emptyList())
    dao.saveCluster(complete, emptyList())

    val page = dao.findClusters(library.id, DedupClusterStatus.UNPROCESSED, true, DedupEvidenceMaturity.COMPLETE, 0, 20)

    assertThat(page.map { it.cluster.id }).containsExactly(complete.id)
    assertThat(dao.countClusters(library.id, DedupClusterStatus.UNPROCESSED, true, DedupEvidenceMaturity.COMPLETE)).isEqualTo(1)
  }

  @Test
  fun `resolution snapshot supports multiple keepers and active member exclusion`() {
    val now = LocalDateTime.now()
    val cluster =
      DedupCluster(
        "cluster-${UUID.randomUUID()}",
        library.id,
        1,
        DedupClusterStatus.PROCESSING,
        true,
        "A",
        "topology",
        "evidence",
        "state",
        null,
        null,
        null,
        null,
        now,
        now,
        null,
      )
    dao.saveCluster(cluster, emptyList())
    val resolution =
      DedupResolution(
        "resolution-${UUID.randomUUID()}",
        cluster.id,
        1,
        DedupResolutionMode.CUSTOM,
        "plan",
        "{}",
        "{}",
        "{}",
        1,
        DedupResolutionState.PROCESSING,
        "admin",
        "{}",
        "token",
        now.plusMinutes(5),
        now,
        now,
        null,
      )
    val members =
      listOf("A", "B").map { id ->
        DedupResolutionMember(
          resolution.id,
          id,
          "series-$id",
          library.id,
          DedupResolutionAction.KEEP,
          null,
          id,
          "/tmp/$id.cbz",
          "{}",
          "{}",
          null,
          null,
          null,
          null,
          null,
          DedupResolutionMemberState.PLANNED,
          null,
          null,
          null,
          now,
          now,
        )
      }

    dao.insertResolution(resolution, members)

    assertThat(dao.findResolutionMembers(resolution.id)).hasSize(2).allMatch { it.action == DedupResolutionAction.KEEP }
    assertThat(dao.hasActiveResolutionForBooks(setOf("B"))).isTrue()
    assertThat(dao.hasResolutionAttempt(cluster.id, 1, DedupResolutionMode.CUSTOM, "admin")).isTrue()
    assertThat(dao.hasResolutionAttempt(cluster.id, 2, DedupResolutionMode.CUSTOM, "admin")).isFalse()
    assertThat(dao.hasResolutionAttempt(cluster.id, 1, DedupResolutionMode.SUGGESTED, "admin")).isFalse()
    assertThat(dao.countResolutionsByState()[DedupResolutionState.PROCESSING]).isGreaterThanOrEqualTo(1)
  }

  @Test
  fun `deleting a Library cascades its cluster resolution audit without blocking native deletion`() {
    val now = LocalDateTime.now()
    val cluster = DedupCluster("cluster-${UUID.randomUUID()}", library.id, 1, DedupClusterStatus.PROCESSING, true, "A", "topology", "evidence", "state", null, null, null, null, now, now, null)
    dao.saveCluster(cluster, emptyList())
    val resolution =
      DedupResolution(
        "resolution-${UUID.randomUUID()}",
        cluster.id,
        1,
        DedupResolutionMode.CUSTOM,
        "plan",
        "{}",
        "{}",
        "{}",
        1,
        DedupResolutionState.PROCESSING,
        "admin",
        "{}",
        "token",
        now.plusMinutes(5),
        now,
        now,
        null,
      )
    dao.insertResolution(resolution, emptyList())

    libraryDao.delete(library.id)

    assertThat(libraryDao.findByIdOrNull(library.id)).isNull()
    assertThat(dao.findResolution(resolution.id)).isNull()
    assertThat(dao.findCluster(cluster.id)).isNull()
  }

  @Test
  fun `Gorse desired state completion is compare and set on hidden intent`() {
    val series = "series-${UUID.randomUUID()}"
    val now = LocalDateTime.now()
    dao.enqueueGorseSync(series, library.id, true, now)
    dao.enqueueGorseSync(series, library.id, false, now.plusSeconds(1))

    assertThat(dao.completeGorseSync(series, true, now.plusSeconds(2))).isFalse()
    assertThat(dao.completeGorseSync(series, false, now.plusSeconds(2))).isTrue()
    assertThat(dao.findGorseSync(series)?.state).isEqualTo("SUCCEEDED")
  }

  @Test
  fun `expired Gorse running claims are recoverable without reclaiming active work`() {
    val series = "series-${UUID.randomUUID()}"
    val now = LocalDateTime.of(2026, 8, 4, 12, 0)
    dao.enqueueGorseSync(series, library.id, true, now)

    assertThat(dao.findPendingGorseSync(now)?.state).isEqualTo("RUNNING")
    assertThat(dao.findPendingGorseSync(now.plusMinutes(9))).isNull()
    assertThat(dao.findPendingGorseSync(now.plusMinutes(11))?.state).isEqualTo("RUNNING")
  }

  private fun relation(
    libraryId: String,
    firstBookId: String,
    secondBookId: String,
  ): DedupRelation {
    val low = minOf(firstBookId, secondBookId)
    val high = maxOf(firstBookId, secondBookId)
    return DedupRelation(
      id = "relation-$low-$high",
      libraryId = libraryId,
      bookLowId = low,
      bookHighId = high,
      lowContentGeneration = "content-$low",
      highContentGeneration = "content-$high",
      type = org.gotson.komga.domain.model.DedupRelationType.EXACT_FILE,
    )
  }
}
