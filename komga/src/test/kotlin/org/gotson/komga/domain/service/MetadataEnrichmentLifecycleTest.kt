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
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentBucketResult
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentDictionaryService
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSettingsProvider
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSource
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSourceExtractor
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentTagIndex
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentTagResult
import org.junit.jupiter.api.Test

class MetadataEnrichmentLifecycleTest {
  private val mapper = jacksonObjectMapper()

  @Test
  fun `source change stales expensive processors and queues only cheap processors`() {
    val stateRepository = mockk<MetadataEnrichmentStateRepository>()
    val bookRepository = mockk<BookRepository>()
    val mediaRepository = mockk<MediaRepository>()
    val sourceExtractor = mockk<MetadataEnrichmentSourceExtractor>()
    val tagIndex = mockk<MetadataEnrichmentTagIndex>(relaxed = true)
    val taskEmitter = mockk<TaskEmitter>()
    val book = makeBook("book.cbz", libraryId = "library", seriesId = "series", id = "book")
    val source = source(summary = "new summary", tagCount = 2)
    val states =
      MetadataEnrichmentProcessor.entries.associateWith { processor ->
        MetadataEnrichmentState(
          bookId = book.id,
          processor = processor,
          status = MetadataEnrichmentStatus.SUCCESS,
          revision = 1,
          inputHash = "old",
          inputJson = mapper.writeValueAsString(source.copy(summary = "old summary")),
          resultJson = "{\"old\":true}",
          resultRevision = 1,
        )
      }
    val saved = mutableListOf<MetadataEnrichmentState>()
    val scheduled = mutableListOf<MetadataEnrichmentProcessor>()

    every { bookRepository.findByIdOrNull(book.id) } returns book
    every { mediaRepository.findByIdOrNull(book.id) } returns Media(bookId = book.id, pageCount = source.pageCount)
    every { sourceExtractor.extract(any()) } returns source
    every { stateRepository.find(book.id, any()) } answers { states[secondArg()] }
    every { stateRepository.save(any<Collection<MetadataEnrichmentState>>()) } answers {
      saved += firstArg<Collection<MetadataEnrichmentState>>()
    }
    every { taskEmitter.enrichMetadata(book.id, any(), any(), any()) } answers {
      scheduled += secondArg<MetadataEnrichmentProcessor>()
    }

    lifecycle(
      stateRepository = stateRepository,
      bookRepository = bookRepository,
      mediaRepository = mediaRepository,
      sourceExtractor = sourceExtractor,
      tagIndex = tagIndex,
      taskEmitter = taskEmitter,
    ).observe(book.id)

    assertThat(saved).hasSize(4)
    assertThat(saved.associate { it.processor to it.status })
      .containsEntry(MetadataEnrichmentProcessor.AI_TITLE, MetadataEnrichmentStatus.STALE)
      .containsEntry(MetadataEnrichmentProcessor.TAG_TRANSLATION, MetadataEnrichmentStatus.STALE)
      .containsEntry(MetadataEnrichmentProcessor.PAGE_SIZE, MetadataEnrichmentStatus.WAITING)
      .containsEntry(MetadataEnrichmentProcessor.TAG_SIZE, MetadataEnrichmentStatus.WAITING)
    assertThat(saved).allMatch { it.revision == 2L && it.resultJson == "{\"old\":true}" }
    assertThat(scheduled).containsExactlyInAnyOrder(MetadataEnrichmentProcessor.PAGE_SIZE, MetadataEnrichmentProcessor.TAG_SIZE)
    verify(exactly = 1) { tagIndex.invalidate() }
  }

  @Test
  fun `book that is no longer compatible loses all enrichment state`() {
    val stateRepository = mockk<MetadataEnrichmentStateRepository>()
    val bookRepository = mockk<BookRepository>()
    val mediaRepository = mockk<MediaRepository>()
    val sourceExtractor = mockk<MetadataEnrichmentSourceExtractor>()
    val tagIndex = mockk<MetadataEnrichmentTagIndex>(relaxed = true)
    val book = makeBook("book.cbz", id = "book")

    every { bookRepository.findByIdOrNull(book.id) } returns book
    every { mediaRepository.findByIdOrNull(book.id) } returns Media(bookId = book.id)
    every { sourceExtractor.extract(any()) } returns null
    every { stateRepository.deleteByBookId(book.id) } returns 4

    assertThat(
      lifecycle(
        stateRepository = stateRepository,
        bookRepository = bookRepository,
        mediaRepository = mediaRepository,
        sourceExtractor = sourceExtractor,
        tagIndex = tagIndex,
      ).observe(book.id),
    ).isFalse()

    verify(exactly = 1) { stateRepository.deleteByBookId(book.id) }
    verify(exactly = 1) { tagIndex.invalidate() }
  }

  @Test
  fun `legacy results remain visible while cheap buckets are queued for correction`() {
    val stateRepository = mockk<MetadataEnrichmentStateRepository>()
    val bookRepository = mockk<BookRepository>()
    val mediaRepository = mockk<MediaRepository>()
    val sourceExtractor = mockk<MetadataEnrichmentSourceExtractor>()
    val dictionary = mockk<MetadataEnrichmentDictionaryService>()
    val book = makeBook("book.cbz", libraryId = "library", seriesId = "series", id = "book")
    val source =
      source(tagCount = 2).copy(
        originalTitle = "旧标题",
        originalSeries = "旧标题",
        existingTags = linkedSetOf("character_旧角色", "旧标签", "pageSize_11-30", "tagSize_0-5"),
        legacyProcessed = true,
      )
    val saved = mutableListOf<MetadataEnrichmentState>()

    every { bookRepository.findByIdOrNull(book.id) } returns book
    every { mediaRepository.findByIdOrNull(book.id) } returns Media(bookId = book.id, pageCount = source.pageCount)
    every { sourceExtractor.extract(any()) } returns source
    every { stateRepository.find(book.id, any()) } returns null
    every { stateRepository.save(any<Collection<MetadataEnrichmentState>>()) } answers {
      saved += firstArg<Collection<MetadataEnrichmentState>>()
    }
    every { dictionary.lookup(any(), any()) } returns "新字典翻译"

    lifecycle(
      stateRepository = stateRepository,
      bookRepository = bookRepository,
      mediaRepository = mediaRepository,
      sourceExtractor = sourceExtractor,
      dictionaryService = dictionary,
    ).observe(book.id)

    val translation =
      saved.single { it.processor == MetadataEnrichmentProcessor.TAG_TRANSLATION }.resultJson!!.let {
        mapper.readValue(it, MetadataEnrichmentTagResult::class.java)
      }
    assertThat(translation.mapping)
      .containsEntry("character:tag-1", "character_旧角色")
      .containsEntry("tag:tag-2", "旧标签")
    assertThat(translation.exactTags).containsExactlyInAnyOrder("character_旧角色", "旧标签")

    val pageSize = saved.single { it.processor == MetadataEnrichmentProcessor.PAGE_SIZE }
    val tagSize = saved.single { it.processor == MetadataEnrichmentProcessor.TAG_SIZE }
    assertThat(pageSize.status).isEqualTo(MetadataEnrichmentStatus.WAITING)
    assertThat(tagSize.status).isEqualTo(MetadataEnrichmentStatus.WAITING)
    assertThat(mapper.readValue(pageSize.resultJson!!, MetadataEnrichmentBucketResult::class.java).label).isEqualTo("pageSize_11-30")
    assertThat(mapper.readValue(tagSize.resultJson!!, MetadataEnrichmentBucketResult::class.java).label).isEqualTo("tagSize_0-5")
  }

  @Test
  fun `tagSize uses deduplicated raw source tag count`() {
    val stateRepository = mockk<MetadataEnrichmentStateRepository>()
    val bookRepository = mockk<BookRepository>()
    val settings = mockk<MetadataEnrichmentSettingsProvider>()
    val source = source(tagCount = 6)
    val state =
      MetadataEnrichmentState(
        bookId = "book",
        processor = MetadataEnrichmentProcessor.TAG_SIZE,
        status = MetadataEnrichmentStatus.RUNNING,
        revision = 3,
        inputHash = "hash",
        inputJson = mapper.writeValueAsString(source),
      )
    var result = ""

    every { stateRepository.markRunning("book", MetadataEnrichmentProcessor.TAG_SIZE, 3) } returns true
    every { stateRepository.find("book", MetadataEnrichmentProcessor.TAG_SIZE) } returns state
    every { stateRepository.markSuccess("book", MetadataEnrichmentProcessor.TAG_SIZE, 3, any()) } answers {
      result = arg(3)
      true
    }
    every { settings.tagSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_TAG_SIZE_BUCKETS
    every { bookRepository.findByIdOrNull("book") } returns null

    lifecycle(stateRepository = stateRepository, bookRepository = bookRepository, settings = settings)
      .process("book", MetadataEnrichmentProcessor.TAG_SIZE, 3, 0)

    assertThat(mapper.readValue(result, MetadataEnrichmentBucketResult::class.java).label).isEqualTo("tagSize_6-10")
  }

  @Test
  fun `old revision task is skipped before an AI request`() {
    val stateRepository = mockk<MetadataEnrichmentStateRepository>()
    val settings = mockk<MetadataEnrichmentSettingsProvider>()
    val aiClient = mockk<MetadataEnrichmentAiClient>()
    every { settings.aiEnabled } returns true
    every { settings.aiConfigured } returns true
    every { stateRepository.markRunning("book", MetadataEnrichmentProcessor.AI_TITLE, 1) } returns false

    lifecycle(stateRepository = stateRepository, settings = settings, aiClient = aiClient)
      .process("book", MetadataEnrichmentProcessor.AI_TITLE, 1, 0)

    verify(exactly = 0) { aiClient.translate(any()) }
  }

  @Test
  fun `task that loses its revision after claiming work is skipped before an AI request`() {
    val stateRepository = mockk<MetadataEnrichmentStateRepository>()
    val settings = mockk<MetadataEnrichmentSettingsProvider>()
    val aiClient = mockk<MetadataEnrichmentAiClient>()
    every { settings.aiEnabled } returns true
    every { settings.aiConfigured } returns true
    every { stateRepository.markRunning("book", MetadataEnrichmentProcessor.AI_TITLE, 1) } returns true
    every { stateRepository.find("book", MetadataEnrichmentProcessor.AI_TITLE) } returns
      MetadataEnrichmentState(
        bookId = "book",
        processor = MetadataEnrichmentProcessor.AI_TITLE,
        status = MetadataEnrichmentStatus.WAITING,
        revision = 2,
        inputHash = "new",
        inputJson = mapper.writeValueAsString(source()),
      )

    lifecycle(stateRepository = stateRepository, settings = settings, aiClient = aiClient)
      .process("book", MetadataEnrichmentProcessor.AI_TITLE, 1, 0)

    verify(exactly = 0) { aiClient.translate(any()) }
  }

  private fun lifecycle(
    stateRepository: MetadataEnrichmentStateRepository = mockk(relaxed = true),
    bookRepository: BookRepository = mockk(relaxed = true),
    mediaRepository: MediaRepository = mockk(relaxed = true),
    sourceExtractor: MetadataEnrichmentSourceExtractor = mockk(relaxed = true),
    tagIndex: MetadataEnrichmentTagIndex = mockk(relaxed = true),
    settings: MetadataEnrichmentSettingsProvider = mockk(relaxed = true),
    aiClient: MetadataEnrichmentAiClient = mockk(relaxed = true),
    taskEmitter: TaskEmitter = mockk(relaxed = true),
    dictionaryService: MetadataEnrichmentDictionaryService = mockk(relaxed = true),
  ) = MetadataEnrichmentLifecycle(
    stateRepository = stateRepository,
    bookRepository = bookRepository,
    mediaRepository = mediaRepository,
    sourceExtractor = sourceExtractor,
    dictionaryService = dictionaryService,
    tagIndex = tagIndex,
    aiClient = aiClient,
    settings = settings,
    taskEmitter = taskEmitter,
    objectMapper = mapper,
  )

  private fun source(
    summary: String = "summary",
    tagCount: Int = 1,
  ) = MetadataEnrichmentSource(
    galleryId = 1,
    sourceRevision = "revision",
    originalTitle = "title",
    originalSeries = "series",
    summary = summary,
    pageCount = 151,
    tags = (1..tagCount).map { MetadataEnrichmentSourceTag(if (it == 1) "character" else "tag", "tag-$it") },
    existingTags = emptySet(),
    legacyProcessed = false,
  )
}
