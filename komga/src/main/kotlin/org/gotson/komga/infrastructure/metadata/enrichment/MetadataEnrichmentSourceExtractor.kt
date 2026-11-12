package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.MetadataEnrichmentSourceTag
import org.gotson.komga.domain.service.BookAnalyzer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

private val sourceLogger = KotlinLogging.logger {}

@Service
class MetadataEnrichmentSourceExtractor(
  private val bookAnalyzer: BookAnalyzer,
  private val objectMapper: ObjectMapper,
  @param:Autowired(required = false) private val xmlMapper: XmlMapper = XmlMapper(),
) {
  fun extract(book: BookWithMedia): MetadataEnrichmentSource? {
    val metadata = read(book, "metadata.json", objectMapper)
    val comicInfo = read(book, "ComicInfo.xml", xmlMapper)
    val tags = mutableListOf<MetadataEnrichmentSourceTag>()
    val fields = mapOf("tag" to "Tags", "artist" to "Writer", "group" to "Groups", "character" to "Characters", "genre" to "Genre")
    fields.forEach { (type, xmlField) ->
      val jsonField = if (type == "genre" && metadata?.has("genre") != true) "category" else type
      val values =
        if (metadata?.path(jsonField)?.isArray == true)
          metadata.path(jsonField).map { it.asText() }
        else
          comicInfo?.path(xmlField)?.asText()?.split(if (type == "artist") Regex(",| & ") else Regex(",")) ?: emptyList()
      values.map(String::trim).filter { it.isNotBlank() && !it.equals("unknown", true) }.forEach { value ->
        if (!technicalTag(value)) tags += MetadataEnrichmentSourceTag(type, value)
      }
    }
    val title =
      metadata?.path("title")?.asText()?.takeIf(String::isNotBlank)
        ?: comicInfo?.path("Title")?.asText().orEmpty()
    val summary =
      metadata?.path("subtitle")?.asText()?.takeIf(String::isNotBlank)
        ?: comicInfo?.path("Summary")?.asText().orEmpty()
    val url = metadata?.path("URL")?.asText(metadata.path("url").asText("")) ?: comicInfo?.path("URL")?.asText().orEmpty()
    return MetadataEnrichmentSource(
      galleryId =
        Regex("/g/(\\d+)")
          .find(url)
          ?.groupValues
          ?.get(1)
          ?.toLongOrNull(),
      sourceRevision = revision(book),
      sourceUrl = url,
      originalTitle = title,
      originalSeries = comicInfo?.path("Series")?.asText().orEmpty(),
      summary = summary,
      pageCount = book.media.pageCount,
      tags = tags.distinctBy { it.key },
      contentTagsKnown = metadata?.path("tag")?.isArray == true || comicInfo?.has("Tags") == true,
      contentTagsFromJson = metadata?.path("tag")?.isArray == true,
    )
  }

  private fun read(
    book: BookWithMedia,
    name: String,
    mapper: ObjectMapper,
  ): JsonNode? {
    val entry = book.media.files.firstOrNull { it.fileName.equals(name, true) } ?: return null
    return try {
      mapper.readTree(bookAnalyzer.getFileContent(book, entry.fileName))
    } catch (exception: Exception) {
      sourceLogger.warn { "Could not read $name for book ${book.book.id}: ${exception.javaClass.simpleName}" }
      null
    }
  }

  companion object {
    fun revision(book: BookWithMedia): String {
      val metadataFiles =
        book.media.files
          .map { it.fileName.lowercase() }
          .filter { it == "metadata.json" || it == "comicinfo.xml" }
          .distinct()
          .sorted()
          .joinToString(",")
      return listOf(book.book.fileLastModified, book.book.fileSize, book.book.fileHash, metadataFiles).joinToString("|")
    }

    fun technicalTag(value: String): Boolean = value.startsWith("pagesize_", true) || value.startsWith("tagsize_", true)
  }
}
