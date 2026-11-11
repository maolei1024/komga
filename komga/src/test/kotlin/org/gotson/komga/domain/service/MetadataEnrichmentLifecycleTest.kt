package org.gotson.komga.domain.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentSourceTag
import org.gotson.komga.domain.model.MetadataEnrichmentState
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentAiClient
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentDictionaryService
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSettingsProvider
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSource
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSourceExtractor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class MetadataEnrichmentLifecycleTest {
  private val mapper = jacksonObjectMapper()
  private val states = mockk<MetadataEnrichmentStateRepository>(relaxed = true)
  private val books = mockk<BookRepository>()
  private val media = mockk<MediaRepository>()
  private val extractor = mockk<MetadataEnrichmentSourceExtractor>()
  private val dictionary = mockk<MetadataEnrichmentDictionaryService>()
  private val ai = mockk<MetadataEnrichmentAiClient>()
  private val settings = mockk<MetadataEnrichmentSettingsProvider>()
  private val tasks = mockk<TaskEmitter>(relaxed = true)
  private val lifecycle = MetadataEnrichmentLifecycle(states, books, media, extractor, dictionary, mockk(relaxed = true), ai, settings, tasks, mapper)
  private val source =
    MetadataEnrichmentSource(
      sourceRevision = "source",
      originalTitle = "Title",
      originalSeries = "",
      summary = "Summary",
      pageCount = 30,
      tags = listOf(MetadataEnrichmentSourceTag("tag", "one")),
      contentTagsKnown = true,
    )

  @Test
  fun `source changes queue one metadata job and leave existing AI title untouched`() {
    val book = makeBook("book.cbz", id = "book")
    every { books.findByIdOrNull("book") } returns book
    every { media.findByIdOrNull("book") } returns Media(bookId = "book", status = Media.Status.READY)
    every { extractor.extract(any()) } returns source
    every { dictionary.fingerprint() } returns "dictionary"
    every { settings.pageSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_PAGE_SIZE_BUCKETS
    every { settings.tagSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_TAG_SIZE_BUCKETS
    every { states.find("book", any()) } answers {
      MetadataEnrichmentState("book", secondArg(), MetadataEnrichmentStatus.SUCCESS, 1, "old", mapper.writeValueAsString(source), """{"title":"Saved"}""", 1)
    }
    val saved = mutableListOf<MetadataEnrichmentState>()
    every { states.save(any<Collection<MetadataEnrichmentState>>()) } answers { saved += firstArg<Collection<MetadataEnrichmentState>>() }
    assertThat(lifecycle.observe("book")).isTrue()
    assertThat(saved).hasSize(2)
    assertThat(saved.single { it.processor == MetadataEnrichmentProcessor.METADATA }.status).isEqualTo(MetadataEnrichmentStatus.WAITING)
    assertThat(saved.single { it.processor == MetadataEnrichmentProcessor.AI_TITLE }.status).isEqualTo(MetadataEnrichmentStatus.STALE)
    verify(exactly = 1) { tasks.enrichMetadata("book", MetadataEnrichmentProcessor.METADATA, 2, any()) }
    verify(exactly = 0) { ai.translate(any()) }
  }

  @Test
  fun `obsolete work cannot publish a result`() {
    every { states.markRunning("book", MetadataEnrichmentProcessor.METADATA, 1) } returns false
    lifecycle.process("book", MetadataEnrichmentProcessor.METADATA, 1, 0)
    verify(exactly = 0) { states.markSuccess(any(), any(), any(), any()) }
  }

  @Test
  fun `repeated scans use the source snapshot and do not queue more work or AI`() {
    val book = makeBook("book.cbz", id = "book")
    val analyzed = Media(bookId = "book", pageCount = 30, status = Media.Status.READY)
    val snapshot =
      source.copy(
        sourceRevision =
          MetadataEnrichmentSourceExtractor.revision(
            org.gotson.komga.domain.model
              .BookWithMedia(book, analyzed),
          ),
      )
    val saved = mutableMapOf<MetadataEnrichmentProcessor, MetadataEnrichmentState>()
    every { books.findByIdOrNull("book") } returns book
    every { media.findByIdOrNull("book") } returns analyzed
    every { states.find("book", any()) } answers { saved[secondArg()] }
    every { extractor.extract(any()) } returns snapshot
    every { states.save(any<Collection<MetadataEnrichmentState>>()) } answers {
      firstArg<Collection<MetadataEnrichmentState>>().forEach { saved[it.processor] = it }
    }
    every { dictionary.fingerprint() } returns "dictionary"
    every { settings.pageSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_PAGE_SIZE_BUCKETS
    every { settings.tagSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_TAG_SIZE_BUCKETS
    every { settings.aiEnabled } returns true
    every { settings.aiConfigured } returns true
    every { settings.aiAutoOnNew } returns true
    lifecycle.observe("book")
    lifecycle.observe("book")
    verify(exactly = 1) { extractor.extract(any()) }
    verify(exactly = 1) { tasks.enrichMetadata("book", MetadataEnrichmentProcessor.METADATA, 1, any()) }
    verify(exactly = 1) { tasks.enrichMetadata("book", MetadataEnrichmentProcessor.AI_TITLE, 1, any()) }
  }

  @Test
  fun `AI failure does not prevent the independent metadata task from completing`() {
    val aiState = MetadataEnrichmentState("book", MetadataEnrichmentProcessor.AI_TITLE, MetadataEnrichmentStatus.RUNNING, 1, "hash", mapper.writeValueAsString(source))
    every { settings.aiEnabled } returns true
    every { settings.aiConfigured } returns true
    every { states.markRunning("book", any(), 1) } returns true
    every { states.find("book", any()) } answers { aiState.copy(processor = secondArg()) }
    every { ai.translate(any()) } throws IllegalStateException("unavailable")
    lifecycle.process("book", MetadataEnrichmentProcessor.AI_TITLE, 1, 0)
    every { dictionary.lookup(any(), any()) } returns "Translated"
    every { settings.pageSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_PAGE_SIZE_BUCKETS
    every { settings.tagSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_TAG_SIZE_BUCKETS
    every { states.markSuccess(any(), any(), any(), any()) } returns false
    lifecycle.process("book", MetadataEnrichmentProcessor.METADATA, 1, 0)
    verify { states.markFailure("book", MetadataEnrichmentProcessor.AI_TITLE, 1, "IllegalStateException") }
    verify { states.markSuccess("book", MetadataEnrichmentProcessor.METADATA, 1, any()) }
  }

  @Test
  fun `metadata observed before analysis is not cached as an empty source`() {
    every { books.findByIdOrNull("book") } returns makeBook("book.cbz", id = "book")
    every { media.findByIdOrNull("book") } returns Media(bookId = "book", status = Media.Status.UNKNOWN)
    assertThat(lifecycle.observe("book")).isFalse()
    verify(exactly = 0) { extractor.extract(any()) }
    verify(exactly = 0) { states.save(any<Collection<MetadataEnrichmentState>>()) }
  }

  @Test
  fun `snapshot round trip preserves source categories without derived properties`() {
    val decoded = mapper.readValue(mapper.writeValueAsString(source), MetadataEnrichmentSource::class.java)
    assertThat(decoded).isEqualTo(source)
    assertThat(decoded.contentTagCount).isEqualTo(1)
  }
}
