package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Author
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
  @Test
  fun `display tags exclude authors and buckets while unknown tags remain visible`() {
    val mapper = jacksonObjectMapper()
    val states = mockk<MetadataEnrichmentStateRepository>()
    val book = BookWithMedia(makeBook("book.cbz", id = "book"), Media(bookId = "book"))
    val source =
      MetadataEnrichmentSource(
        sourceRevision = "",
        originalTitle = "",
        originalSeries = "",
        summary = "",
        pageCount = 30,
        tags = listOf(MetadataEnrichmentSourceTag("tag", "one"), MetadataEnrichmentSourceTag("artist", "Artist")),
        contentTagsKnown = false,
        unclassifiedTags = setOf("unclassified"),
      )
    every { states.findAllByBookId("book") } returns
      listOf(
        MetadataEnrichmentState(
          "book",
          MetadataEnrichmentProcessor.METADATA,
          MetadataEnrichmentStatus.SUCCESS,
          1,
          "",
          mapper.writeValueAsString(source),
          mapper.writeValueAsString(MetadataEnrichmentTagResult(mapOf("tag:one" to "翻译", "artist:artist" to "作者译名"), listOf("pagesize_11-30"))),
          1,
        ),
      )
    val provider = MetadataEnrichmentProvider(states, mockk<BookRepository>(), mapper)
    val patch = provider.getBookMetadataFromBook(book)!!
    assertThat(patch.tags).containsExactlyInAnyOrder("翻译", "unclassified")
    assertThat(patch.authors).containsExactly(Author("作者译名", "writer"))
  }
}
