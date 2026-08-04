package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupLibrarySettings
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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DedupDaoTest(
  @Autowired private val dao: DedupDao,
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
    val value = DedupLibrarySettings(library.id, enabled = true, paused = true, batchSize = 25, maxDurationSeconds = 45, quietPeriodSeconds = 120)
    dao.saveLibrarySettings(value)
    assertThat(dao.findLibrarySettings(library.id)).usingRecursiveComparison().ignoringFields("createdDate", "lastModifiedDate").isEqualTo(value)
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
}
