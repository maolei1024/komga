package org.gotson.komga.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.infrastructure.jooq.main.BookDao
import org.gotson.komga.infrastructure.jooq.main.DedupDao
import org.gotson.komga.infrastructure.jooq.main.LibraryDao
import org.gotson.komga.infrastructure.jooq.main.SeriesDao
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext
import java.net.URL
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DedupWorkLifecycleTest(
  @Autowired private val lifecycle: DedupWorkLifecycle,
  @Autowired private val dao: DedupDao,
  @Autowired private val libraryDao: LibraryDao,
  @Autowired private val seriesDao: SeriesDao,
  @Autowired private val bookDao: BookDao,
) {
  @Test
  fun `enabling automatic suggestions queues an immediate automatic resolution sweep`() {
    val library = makeLibrary("dedup-auto-${UUID.randomUUID()}")
    libraryDao.insert(library)

    lifecycle.saveSettings(DedupLibrarySettings(library.id, enabled = true, autoResolveSuggestions = true))

    assertThat(dao.findAllWork().filter { it.libraryId == library.id })
      .anyMatch { it.type == DedupWorkType.AUTO_RESOLVE_SUGGESTIONS }
  }

  @Test
  fun `cluster rebuild queues automatic suggestions after current evidence is saved`() {
    val library = makeLibrary("dedup-auto-rebuild-${UUID.randomUUID()}")
    libraryDao.insert(library)
    dao.saveLibrarySettings(DedupLibrarySettings(library.id, enabled = true, autoResolveSuggestions = true))
    dao.enqueueWork("rebuild-${UUID.randomUUID()}", library.id, DedupWorkType.REBUILD_CLUSTERS)

    lifecycle.drain(library.id)

    assertThat(dao.findAllWork().filter { it.libraryId == library.id && it.type == DedupWorkType.AUTO_RESOLVE_SUGGESTIONS })
      .singleElement()
      .extracting("state")
      .isEqualTo(DedupWorkState.SUCCEEDED)
  }

  @Test
  fun `250 unscanned Books with N 100 are processed in 100 100 50 batches`() {
    val fixture = fixture(250, batchSize = 100)

    lifecycle.drain(fixture.libraryId)
    assertThat(dao.findFeaturesByLibrary(fixture.libraryId)).hasSize(100)
    assertThat(dao.findLibrarySettings(fixture.libraryId)?.lastBatchBookCount).isEqualTo(100)

    lifecycle.drain(fixture.libraryId)
    assertThat(dao.findFeaturesByLibrary(fixture.libraryId)).hasSize(200)
    assertThat(dao.findLibrarySettings(fixture.libraryId)?.lastBatchBookCount).isEqualTo(100)

    lifecycle.drain(fixture.libraryId)
    assertThat(dao.findFeaturesByLibrary(fixture.libraryId)).hasSize(250)
    assertThat(dao.findLibrarySettings(fixture.libraryId)?.lastBatchBookCount).isEqualTo(50)
    assertThat(
      dao.findAllWork().count { it.libraryId == fixture.libraryId && it.type == DedupWorkType.SCAN_BOOK && it.state == DedupWorkState.SUCCEEDED },
    ).isEqualTo(250)
  }

  @Test
  fun `internal rebuild work does not consume the Book allowance`() {
    val fixture = fixture(60, batchSize = 25)
    dao.enqueueWork("rebuild-${UUID.randomUUID()}", fixture.libraryId, DedupWorkType.REBUILD_CLUSTERS)

    lifecycle.drain(fixture.libraryId)

    assertThat(dao.findFeaturesByLibrary(fixture.libraryId)).hasSize(25)
    assertThat(dao.findLibrarySettings(fixture.libraryId)?.lastBatchBookCount).isEqualTo(25)
    assertThat(dao.findAllWork().single { it.libraryId == fixture.libraryId && it.type == DedupWorkType.REBUILD_CLUSTERS }.state)
      .isEqualTo(DedupWorkState.SUCCEEDED)
  }

  @Test
  fun `modified soft-deleted and hard-deleted Books coalesce through SCAN_BOOK in a multi-Book Series`() {
    val fixture = fixture(2, batchSize = 10)
    lifecycle.drain(fixture.libraryId)
    val first = bookDao.findByIdOrNull(fixture.bookIds[0])!!
    val second = bookDao.findByIdOrNull(fixture.bookIds[1])!!
    assertThat(dao.findFeature(first.id)).isNotNull
    assertThat(dao.findFeature(second.id)).isNotNull

    val previousMetadata = dao.findFeature(first.id)!!.sourceMetadataGeneration
    bookDao.update(first.copy(name = "renamed.cbz"))
    lifecycle.requestBookScan(fixture.libraryId, first.id, DedupWorkLifecycle.PRIORITY_UPDATED)
    lifecycle.requestBookScan(fixture.libraryId, first.id, DedupWorkLifecycle.PRIORITY_UPDATED)
    lifecycle.drain(fixture.libraryId)
    assertThat(dao.findFeature(first.id)?.sourceMetadataGeneration).isNotEqualTo(previousMetadata)

    bookDao.update(bookDao.findByIdOrNull(first.id)!!.copy(deletedDate = LocalDateTime.now()))
    lifecycle.requestBookScan(fixture.libraryId, first.id, DedupWorkLifecycle.PRIORITY_DELETED)
    lifecycle.drain(fixture.libraryId)
    assertThat(dao.findFeature(first.id)).isNull()

    lifecycle.requestBookScan(fixture.libraryId, second.id, DedupWorkLifecycle.PRIORITY_DELETED)
    bookDao.delete(second.id)
    lifecycle.drain(fixture.libraryId)
    assertThat(dao.findFeature(second.id)).isNull()
  }

  private fun fixture(
    count: Int,
    batchSize: Int,
  ): Fixture {
    val suffix = UUID.randomUUID().toString()
    val library = makeLibrary("dedup-work-$suffix")
    libraryDao.insert(library)
    val series = makeSeries("series-$suffix", library.id)
    seriesDao.insert(series)
    val now = LocalDateTime.now()
    val books =
      (1..count).map { index ->
        val id = "book-$suffix-${index.toString().padStart(4, '0')}"
        Book(
          name = "$index.cbz",
          url = URL("file:/tmp/$id.cbz"),
          fileLastModified = now,
          fileSize = 100,
          id = id,
          seriesId = series.id,
          libraryId = library.id,
          createdDate = now.plusNanos(index.toLong()),
          lastModifiedDate = now.plusNanos(index.toLong()),
        )
      }
    bookDao.insert(books)
    dao.saveLibrarySettings(DedupLibrarySettings(library.id, enabled = true, batchSize = batchSize, quietPeriodSeconds = 0))
    return Fixture(library.id, books.map { it.id })
  }

  private data class Fixture(
    val libraryId: String,
    val bookIds: List<String>,
  )
}
