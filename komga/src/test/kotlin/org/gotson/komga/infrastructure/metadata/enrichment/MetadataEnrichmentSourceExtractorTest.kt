package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MediaFile
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.service.BookAnalyzer
import org.junit.jupiter.api.Test

class MetadataEnrichmentSourceExtractorTest {
  private val analyzer = mockk<BookAnalyzer>()
  private val extractor = MetadataEnrichmentSourceExtractor(analyzer, jacksonObjectMapper())

  @Test
  fun `JSON works without XML or a supported source URL and counts only content tags`() {
    val book = book("metadata.json")
    every { analyzer.getFileContent(book, "metadata.json") } returns
      """{"URL":"https://example.com/item","tag":["one","ONE","pagesize_10","unknown"],"artist":["A","B"],"group":["Circle"]}""".toByteArray()
    val source = extractor.extract(book)!!
    assertThat(source.pageCount).isEqualTo(42)
    assertThat(source.contentTagCount).isEqualTo(1)
    assertThat(source.tags.map { it.key }).containsExactly("tag:one", "artist:a", "artist:b", "group:circle")
  }

  @Test
  fun `XML works independently and separates creators and characters`() {
    val book = book("ComicInfo.xml")
    every { analyzer.getFileContent(book, "ComicInfo.xml") } returns
      """<ComicInfo><Writer>A &amp; B</Writer><Groups>C</Groups><Characters>D</Characters><Tags>one,two,tagSize_0-5</Tags></ComicInfo>""".toByteArray()
    val source = extractor.extract(book)!!
    assertThat(source.contentTagCount).isEqualTo(2)
    assertThat(source.tags.map { it.key }).contains("artist:a", "artist:b", "group:c", "character:d")
  }

  @Test
  fun `missing metadata is unknown rather than zero and explicit empty tags are zero`() {
    assertThat(extractor.extract(book())!!.contentTagCount).isNull()
    val book = book("metadata.json")
    every { analyzer.getFileContent(book, "metadata.json") } returns """{"tag":[]}""".toByteArray()
    assertThat(extractor.extract(book)!!.contentTagCount).isZero()
  }

  @Test
  fun `broken JSON falls back to valid XML`() {
    val book = book("metadata.json", "ComicInfo.xml")
    every { analyzer.getFileContent(book, "metadata.json") } returns "{".toByteArray()
    every { analyzer.getFileContent(book, "ComicInfo.xml") } returns """<ComicInfo><Tags>one</Tags></ComicInfo>""".toByteArray()
    assertThat(extractor.extract(book)!!.contentTagCount).isEqualTo(1)
  }

  @Test
  fun `JSON categories take precedence while XML supplies missing namespaces`() {
    val book = book("metadata.json", "ComicInfo.xml")
    every { analyzer.getFileContent(book, "metadata.json") } returns
      """{"tag":["Raw"],"artist":["Unknown","First","Second"],"category":["Manga"]}""".toByteArray()
    every { analyzer.getFileContent(book, "ComicInfo.xml") } returns
      """<ComicInfo><Tags>Translated,artist_legacy</Tags><Writer>Combined &amp; Old</Writer><Characters>Character</Characters></ComicInfo>""".toByteArray()
    val source = extractor.extract(book)!!
    assertThat(source.tags.map { it.key }).containsExactly("tag:raw", "artist:first", "artist:second", "character:character", "genre:manga")
    assertThat(source.contentTagCount).isEqualTo(1)
  }

  @Test
  fun `both damaged metadata inputs leave the content count unknown`() {
    val book = book("metadata.json", "ComicInfo.xml")
    every { analyzer.getFileContent(book, any()) } returns "broken".toByteArray()
    assertThat(extractor.extract(book)!!.contentTagCount).isNull()
  }

  private fun book(vararg files: String) =
    BookWithMedia(
      makeBook("book.cbz", id = "book"),
      Media(bookId = "book", pageCount = 42, files = files.map { MediaFile(it) }),
    )
}
