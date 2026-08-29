package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MediaFile
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.service.BookAnalyzer
import org.junit.jupiter.api.Test

class MetadataEnrichmentSourceExtractorTest {
  private val analyzer = mockk<BookAnalyzer>()
  private val extractor = MetadataEnrichmentSourceExtractor(analyzer, jacksonObjectMapper(), XmlMapper())

  @Test
  fun `valid self-nhentai source uses media page count and deduplicated semantic tags`() {
    val book = compatibleBook(pageCount = 42)
    every { analyzer.getFileContent(book, "metadata.json") } returns
      """{"URL":"https://nhentai.net/g/123/","tag":["one","ONE","unknown"],"character":["Alice"],"artist":["Artist"],"group":["Circle"]}""".toByteArray()
    every { analyzer.getFileContent(book, "ComicInfo.xml") } returns
      """<ComicInfo><Title>Raw title</Title><Series>Raw series</Series><Summary>Raw summary</Summary><Tags>old</Tags></ComicInfo>""".toByteArray()

    val source = extractor.extract(book)

    assertThat(source).isNotNull
    assertThat(source!!.galleryId).isEqualTo(123)
    assertThat(source.pageCount).isEqualTo(42)
    assertThat(source.summary).isEqualTo("Raw summary")
    assertThat(source.legacyProcessed).isFalse()
    assertThat(source.tags.map { it.key })
      .containsExactly("tag:one", "character:alice", "artist:artist", "group:circle")
  }

  @Test
  fun `non-nhentai URL is incompatible and does not read ComicInfo`() {
    val book = compatibleBook()
    every { analyzer.getFileContent(book, "metadata.json") } returns
      """{"URL":"https://example.com/g/123","tag":["one"]}""".toByteArray()

    assertThat(extractor.extract(book)).isNull()
    verify(exactly = 0) { analyzer.getFileContent(book, "ComicInfo.xml") }
  }

  private fun compatibleBook(pageCount: Int = 1): BookWithMedia =
    BookWithMedia(
      makeBook("book.cbz", id = "book"),
      Media(
        status = Media.Status.READY,
        mediaType = "application/zip",
        pageCount = pageCount,
        files = listOf(MediaFile("metadata.json"), MediaFile("ComicInfo.xml")),
        bookId = "book",
      ),
    )
}
