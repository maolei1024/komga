package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.MetadataEnrichmentSourceTag
import org.gotson.komga.domain.service.BookAnalyzer
import org.gotson.komga.infrastructure.metadata.comicrack.dto.ComicInfo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class MetadataEnrichmentSourceExtractor(
  private val bookAnalyzer: BookAnalyzer,
  private val objectMapper: ObjectMapper,
  @param:Autowired(required = false) private val xmlMapper: XmlMapper = XmlMapper(),
) {
  fun extract(book: BookWithMedia): MetadataEnrichmentSource? {
    if (book.media.files.none { it.fileName == METADATA_JSON } || book.media.files.none { it.fileName == COMIC_INFO }) return null

    val metadata = objectMapper.readTree(bookAnalyzer.getFileContent(book, METADATA_JSON))
    val url = metadata.path("URL").asText(metadata.path("url").asText(""))
    val galleryId =
      GALLERY_ID
        .find(url)
        ?.groupValues
        ?.getOrNull(1)
        ?.toLongOrNull() ?: return null
    val comicInfo = xmlMapper.readValue(bookAnalyzer.getFileContent(book, COMIC_INFO), ComicInfo::class.java)

    val tags = mutableListOf<MetadataEnrichmentSourceTag>()
    addTags(tags, metadata.path("tag"), "tag")
    addTags(tags, metadata.path("character"), "character")
    addTags(tags, metadata.path("artist"), "artist")
    addTags(tags, metadata.path("group"), "group")
    val distinctTags = tags.distinctBy { it.key }

    val title = comicInfo.title.orEmpty().trim()
    val series = comicInfo.series.orEmpty().trim()
    val existingTags =
      comicInfo.tags
        ?.split(',')
        ?.mapNotNull { it.trim().ifBlank { null } }
        ?.toSet()
        .orEmpty()
    return MetadataEnrichmentSource(
      galleryId = galleryId,
      sourceRevision = listOf(book.book.fileLastModified, book.book.fileSize, book.book.fileHash).joinToString("|"),
      originalTitle = title,
      originalSeries = series,
      summary = comicInfo.summary.orEmpty().trim(),
      pageCount = book.media.pageCount,
      tags = distinctTags,
      existingTags = existingTags,
      legacyProcessed = title.isNotBlank() && title == series,
    )
  }

  private fun addTags(
    target: MutableList<MetadataEnrichmentSourceTag>,
    values: com.fasterxml.jackson.databind.JsonNode,
    type: String,
  ) {
    if (!values.isArray) return
    values.forEach { node ->
      node.asText().trim().takeIf { it.isNotBlank() && !it.equals("unknown", true) }?.let {
        target += MetadataEnrichmentSourceTag(type, it)
      }
    }
  }

  companion object {
    private const val METADATA_JSON = "metadata.json"
    private const val COMIC_INFO = "ComicInfo.xml"
    private val GALLERY_ID = Regex("^https?://(?:www\\.)?nhentai\\.net/g/(\\d+)(?:/.*)?$", RegexOption.IGNORE_CASE)
  }
}
