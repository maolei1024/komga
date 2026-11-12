package org.gotson.komga.infrastructure.gorse

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Author
import org.gotson.komga.domain.model.BookMetadata
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentSourceTag
import org.gotson.komga.domain.model.MetadataEnrichmentState
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSettingsProvider
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSource
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentTagResult
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GorseLabelBuilderTest {
  private val mapper = jacksonObjectMapper()
  private val books = mockk<BookRepository>()
  private val media = mockk<MediaRepository>()
  private val states = mockk<MetadataEnrichmentStateRepository>()
  private val series = mockk<SeriesMetadataRepository>()
  private val metadata = mockk<BookMetadataRepository>()
  private val settings = mockk<MetadataEnrichmentSettingsProvider>()
  private val builder = GorseLabelBuilder(books, media, states, series, metadata, settings, mapper)
  private val book = makeBook("book.cbz", id = "book", seriesId = "series")
  private val source =
    MetadataEnrichmentSource(
      sourceRevision = "source",
      originalTitle = "",
      originalSeries = "",
      summary = "",
      pageCount = 900,
      tags =
        listOf(
          MetadataEnrichmentSourceTag("tag", "Content"),
          MetadataEnrichmentSourceTag("artist", "First"),
          MetadataEnrichmentSourceTag("artist", "Second"),
          MetadataEnrichmentSourceTag("group", "Circle"),
          MetadataEnrichmentSourceTag("character", "Character"),
        ),
      contentTagsKnown = true,
      unclassifiedTags = setOf("Uncertain"),
    )

  @BeforeEach
  fun setup() {
    every { books.findAllBySeriesId("series") } returns listOf(book)
    every { media.findByIdOrNull("book") } returns Media(bookId = "book", pageCount = 30)
    every { series.findByIdOrNull("series") } returns null
    every { metadata.findByIdOrNull("book") } returns BookMetadata("Title", number = "1", numberSort = 1f)
    every { settings.pageSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_PAGE_SIZE_BUCKETS
    every { settings.tagSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_TAG_SIZE_BUCKETS
    setSource(source)
  }

  @Test
  fun `labels separate namespaces and use analyzed pages and raw author arrays`() {
    val labels = builder.build("series")
    assertThat(labels["tags"]).isEqualTo(listOf("content"))
    assertThat(labels["authors"]).isEqualTo(listOf("first", "second"))
    assertThat(labels["groups"]).isEqualTo(listOf("circle"))
    assertThat(labels["characters"]).isEqualTo(listOf("character"))
    assertThat(labels["preference_buckets"]).isEqualTo(listOf("pagesize_11-30", "tagsize_0-5"))
  }

  @Test
  fun `manual locks override raw metadata while translated labels retain stable keys`() {
    every { metadata.findByIdOrNull("book") } returns
      BookMetadata(
        "Title",
        number = "1",
        numberSort = 1f,
        tags = setOf("译名", "Manual", "Uncertain", "pageSize_900+"),
        authors = listOf(Author("Corrected", "writer"), Author("Unknown", "writer")),
        tagsLock = true,
        authorsLock = true,
      )
    val labels = builder.build("series")
    assertThat(labels["tags"]).isEqualTo(listOf("content", "manual"))
    assertThat(labels["authors"]).isEqualTo(listOf("corrected"))
  }

  @Test
  fun `unknown content count does not create a zero bucket`() {
    setSource(source.copy(contentTagsKnown = false))
    assertThat(builder.build("series")["preference_buckets"]).isEqualTo(listOf("pagesize_11-30"))
  }

  @Test
  fun `approved fallback counts current tags without using unclassified content for matching`() {
    setSource(source.copy(contentTagsKnown = false, fallbackTags = setOf("outdated")))
    val currentTags = (1..8).map { "uncertain-$it" }.toSet() + setOf("UNCERTAIN-1", "pageSize_31-50", "tagSize_50+")
    every { metadata.findByIdOrNull("book") } returns BookMetadata("Title", number = "1", numberSort = 1f, tags = currentTags)
    val labels = builder.build("series")
    assertThat(labels["tags"]).isEqualTo(listOf("content"))
    assertThat(labels["preference_buckets"]).isEqualTo(listOf("pagesize_11-30", "tagsize_6-10"))
  }

  @Test
  fun `fallback coverage matches projection and respects manual tag changes`() {
    setSource(source.copy(contentTagsKnown = false, fallbackTags = setOf("old")))
    val fields = BookMetadata("Title", number = "1", numberSort = 1f, bookId = book.id, tags = (1..12).map { "uncertain-$it" }.toSet(), tagsLock = true)
    every { books.findAll() } returns listOf(book)
    every { metadata.findByIdOrNull("book") } returns fields
    every { metadata.findAllByIds(any()) } returns listOf(fields)
    every { media.getPagesSizes(any()) } returns listOf(book.id to 30)
    every { states.findAllByProcessor(MetadataEnrichmentProcessor.METADATA) } returns listOf(states.find(book.id, MetadataEnrichmentProcessor.METADATA)!!)
    val user = KomgaUser("test@example.com", "password", sharedAllLibraries = true)
    assertThat(builder.build("series")["preference_buckets"]).isEqualTo(listOf("pagesize_11-30", "tagsize_11-15"))
    assertThat(builder.coverage(user)).isEqualTo(mapOf(("pagesize_11-30" to "tagsize_11-15") to 1))
  }

  @Test
  fun `a partially unknown series does not claim a complete count from another fallback book`() {
    val secondBook = makeBook("second.cbz", id = "second", seriesId = "series")
    every { books.findAllBySeriesId("series") } returns listOf(book, secondBook)
    every { media.findByIdOrNull("second") } returns Media(bookId = "second", pageCount = 20)
    every { metadata.findByIdOrNull("second") } returns BookMetadata("Second", number = "2", numberSort = 2f)
    every { states.find("second", MetadataEnrichmentProcessor.METADATA) } returns null
    setSource(source.copy(contentTagsKnown = false, fallbackTags = setOf("one")))
    assertThat(builder.build("series")["preference_buckets"]).isEqualTo(listOf("pagesize_31-70"))
  }

  @Test
  fun `coverage uses the same buckets and limits works to the users libraries`() {
    every { books.findAll() } returns listOf(book)
    every { metadata.findAllByIds(any()) } returns listOf(BookMetadata("Title", number = "1", numberSort = 1f, bookId = book.id))
    every { media.getPagesSizes(any()) } returns listOf(book.id to 30)
    every { states.findAllByProcessor(MetadataEnrichmentProcessor.METADATA) } returns listOf(states.find(book.id, MetadataEnrichmentProcessor.METADATA)!!)
    val user = KomgaUser("test@example.com", "password", sharedAllLibraries = false, sharedLibrariesIds = setOf(book.libraryId))
    assertThat(builder.coverage(user)).isEqualTo(mapOf(("pagesize_11-30" to "tagsize_0-5") to 1))
    assertThat(builder.coverage(user.copy(sharedLibrariesIds = emptySet()))).isEmpty()
    assertThat(builder.coverage(user)).isEqualTo(mapOf(("pagesize_11-30" to "tagsize_0-5") to 1))
    io.mockk.verify(exactly = 2) { books.findAll() }
    builder.invalidateCoverage(DomainEvent.BookUpdated(book))
    assertThat(builder.coverage(user)).isEqualTo(mapOf(("pagesize_11-30" to "tagsize_0-5") to 1))
    io.mockk.verify(exactly = 3) { books.findAll() }
    io.mockk.verify(exactly = 0) { series.findByIdOrNull(any()) }
  }

  @Test
  fun `locking a translated author preserves its stable recommendation key`() {
    every { metadata.findByIdOrNull("book") } returns
      BookMetadata(
        "Title",
        number = "1",
        numberSort = 1f,
        authors = listOf(Author("作者一", "writer")),
        authorsLock = true,
      )
    assertThat(builder.build("series")["authors"]).isEqualTo(listOf("first"))
  }

  private fun setSource(value: MetadataEnrichmentSource) {
    every { states.find("book", MetadataEnrichmentProcessor.METADATA) } returns
      MetadataEnrichmentState(
        "book",
        MetadataEnrichmentProcessor.METADATA,
        MetadataEnrichmentStatus.SUCCESS,
        1,
        "hash",
        mapper.writeValueAsString(value),
        mapper.writeValueAsString(MetadataEnrichmentTagResult(mapOf("tag:content" to "译名", "artist:first" to "作者一"))),
        1,
      )
  }
}
