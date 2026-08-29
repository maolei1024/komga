package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentSourceTag
import org.gotson.komga.domain.model.MetadataEnrichmentState
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.junit.jupiter.api.Test

class MetadataEnrichmentProviderTest {
  private val mapper = jacksonObjectMapper()
  private val stateRepository = mockk<MetadataEnrichmentStateRepository>()
  private val bookRepository = mockk<BookRepository>()
  private val provider = MetadataEnrichmentProvider(stateRepository, bookRepository, mapper)

  @Test
  fun `stale translation keeps known mappings and renders new source tags verbatim`() {
    val book = book()
    val source = source(listOf(MetadataEnrichmentSourceTag("tag", "known"), MetadataEnrichmentSourceTag("character", "New Character")))
    val translation = MetadataEnrichmentTagResult(mapping = mapOf("tag:known" to "旧翻译"), exactTags = setOf("旧翻译"))
    every { stateRepository.findAllByBookId("book") } returns
      listOf(
        state(
          MetadataEnrichmentProcessor.TAG_TRANSLATION,
          status = MetadataEnrichmentStatus.STALE,
          revision = 2,
          resultRevision = 1,
          inputJson = mapper.writeValueAsString(source),
          resultJson = mapper.writeValueAsString(translation),
        ),
        state(
          MetadataEnrichmentProcessor.PAGE_SIZE,
          resultJson = mapper.writeValueAsString(MetadataEnrichmentBucketResult("pageSize_11-30")),
        ),
      )

    val patch = provider.getBookMetadataFromBook(book)

    assertThat(patch!!.tags).containsExactlyInAnyOrder("旧翻译", "character_New Character", "pageSize_11-30")
  }

  @Test
  fun `AI result updates title and single-book series title and sort title`() {
    val book = book()
    val ai = state(MetadataEnrichmentProcessor.AI_TITLE, resultJson = mapper.writeValueAsString(MetadataEnrichmentAiResult("中文标题")))
    every { stateRepository.findAllByBookId("book") } returns listOf(ai)
    every { stateRepository.find("book", MetadataEnrichmentProcessor.AI_TITLE) } returns ai
    every { bookRepository.findAllBySeriesId("series") } returns listOf(book.book)

    assertThat(provider.getBookMetadataFromBook(book)!!.title).isEqualTo("中文标题")
    val seriesPatch = provider.getSeriesMetadataFromBook(book, false)!!
    assertThat(seriesPatch.title).isEqualTo("中文标题")
    assertThat(seriesPatch.titleSort).isEqualTo("中文标题")
  }

  private fun book() =
    BookWithMedia(
      makeBook("book.cbz", id = "book", seriesId = "series"),
      Media(bookId = "book"),
    )

  private fun source(tags: List<MetadataEnrichmentSourceTag>) =
    MetadataEnrichmentSource(
      galleryId = 1,
      sourceRevision = "source",
      originalTitle = "title",
      originalSeries = "series",
      summary = "summary",
      pageCount = 20,
      tags = tags,
      existingTags = emptySet(),
      legacyProcessed = false,
    )

  private fun state(
    processor: MetadataEnrichmentProcessor,
    status: MetadataEnrichmentStatus = MetadataEnrichmentStatus.SUCCESS,
    revision: Long = 1,
    resultRevision: Long? = revision,
    inputJson: String = mapper.writeValueAsString(source(emptyList())),
    resultJson: String? = null,
  ) = MetadataEnrichmentState(
    bookId = "book",
    processor = processor,
    status = status,
    revision = revision,
    inputHash = "hash",
    inputJson = inputJson,
    resultJson = resultJson,
    resultRevision = resultRevision,
  )
}
