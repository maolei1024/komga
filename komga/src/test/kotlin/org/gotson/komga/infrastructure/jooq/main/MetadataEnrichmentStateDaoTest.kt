package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentState
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.annotation.DirtiesContext
import java.util.UUID

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MetadataEnrichmentStateDaoTest(
  @Autowired private val dao: MetadataEnrichmentStateDao,
  @Autowired private val libraryDao: LibraryDao,
  @Autowired private val seriesDao: SeriesDao,
  @Autowired private val bookDao: BookDao,
) {
  private val library = makeLibrary("enrichment-${UUID.randomUUID()}")
  private val series = makeSeries("series-${UUID.randomUUID()}", library.id)
  private val book = makeBook("book.cbz", libraryId = library.id, seriesId = series.id)

  @BeforeEach
  fun setup() {
    libraryDao.insert(library)
    seriesDao.insert(series)
    bookDao.insert(book)
  }

  @AfterEach
  fun cleanup() {
    bookDao.deleteAll()
    seriesDao.deleteAll()
    libraryDao.deleteAll()
  }

  @Test
  fun `revision compare and set skips stale work and preserves newest result`() {
    dao.save(state(revision = 1))
    assertThat(dao.markRunning(book.id, MetadataEnrichmentProcessor.AI_TITLE, 1)).isTrue()

    dao.save(state(revision = 2))
    assertThat(dao.markSuccess(book.id, MetadataEnrichmentProcessor.AI_TITLE, 1, "old")).isFalse()
    assertThat(dao.markRunning(book.id, MetadataEnrichmentProcessor.AI_TITLE, 2)).isTrue()
    assertThat(dao.markSuccess(book.id, MetadataEnrichmentProcessor.AI_TITLE, 2, "new")).isTrue()
    assertThat(dao.markFailure(book.id, MetadataEnrichmentProcessor.AI_TITLE, 2, "late failure")).isFalse()

    assertThat(dao.find(book.id, MetadataEnrichmentProcessor.AI_TITLE))
      .extracting("status", "revision", "resultJson", "resultRevision")
      .containsExactly(MetadataEnrichmentStatus.SUCCESS, 2L, "new", 2L)
  }

  @Test
  fun `restart recovery restores running work and statistics include it`() {
    dao.save(state(processor = MetadataEnrichmentProcessor.TAG_TRANSLATION, status = MetadataEnrichmentStatus.RUNNING))

    val recovered = dao.resetRunning()

    assertThat(recovered).singleElement().extracting("status").isEqualTo(MetadataEnrichmentStatus.WAITING)
    assertThat(dao.countByProcessorAndStatus()[MetadataEnrichmentProcessor.TAG_TRANSLATION to MetadataEnrichmentStatus.WAITING]).isEqualTo(1)
    assertThat(dao.findAll(MetadataEnrichmentProcessor.TAG_TRANSLATION, MetadataEnrichmentStatus.WAITING, library.id, PageRequest.of(0, 20)).totalElements)
      .isEqualTo(1)
  }

  @Test
  fun `book deletion cascades enrichment state`() {
    dao.save(state(processor = MetadataEnrichmentProcessor.PAGE_SIZE))

    bookDao.delete(book.id)

    assertThat(dao.findAllByBookId(book.id)).isEmpty()
  }

  private fun state(
    processor: MetadataEnrichmentProcessor = MetadataEnrichmentProcessor.AI_TITLE,
    status: MetadataEnrichmentStatus = MetadataEnrichmentStatus.WAITING,
    revision: Long = 1,
  ) = MetadataEnrichmentState(
    bookId = book.id,
    processor = processor,
    status = status,
    revision = revision,
    inputHash = "hash-$revision",
    inputJson = "{}",
  )
}
