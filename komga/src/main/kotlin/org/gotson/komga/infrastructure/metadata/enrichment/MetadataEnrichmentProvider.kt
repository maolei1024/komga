package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.Author
import org.gotson.komga.domain.model.BookMetadataPatch
import org.gotson.komga.domain.model.BookMetadataPatchCapability
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataPatchTarget
import org.gotson.komga.domain.model.SeriesMetadataPatch
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.gotson.komga.infrastructure.metadata.BookMetadataProvider
import org.gotson.komga.infrastructure.metadata.SeriesMetadataFromBookProvider
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service

@Service
@Order(1000)
class MetadataEnrichmentProvider(
  private val stateRepository: MetadataEnrichmentStateRepository,
  private val bookRepository: BookRepository,
  private val objectMapper: ObjectMapper,
) : BookMetadataProvider,
  SeriesMetadataFromBookProvider {
  override val capabilities = setOf(BookMetadataPatchCapability.TITLE, BookMetadataPatchCapability.TAGS, BookMetadataPatchCapability.AUTHORS)
  override val supportsAppendVolume = false

  override fun getBookMetadataFromBook(book: BookWithMedia): BookMetadataPatch? {
    val states = stateRepository.findAllByBookId(book.book.id).associateBy { it.processor }
    val title =
      states[MetadataEnrichmentProcessor.AI_TITLE]?.resultJson?.let {
        objectMapper.readValue(it, MetadataEnrichmentAiResult::class.java).title
      }
    val state = states[MetadataEnrichmentProcessor.METADATA] ?: return title?.let { BookMetadataPatch(title = it) }
    val source = objectMapper.readValue(state.inputJson, MetadataEnrichmentSource::class.java)
    val result =
      state.resultJson?.takeIf { state.resultRevision == state.revision }?.let {
        objectMapper.readValue(it, MetadataEnrichmentTagResult::class.java)
      }
    val tags =
      source.tags
        .filter { it.normalizedType == "tag" }
        .map {
          result?.mapping?.get(it.key) ?: it.value
        }.toSet() + source.unclassifiedTags
    val authors = source.tags.filter { it.normalizedType == "artist" }.map { Author(result?.mapping?.get(it.key) ?: it.value, "writer") }
    return BookMetadataPatch(title = title, tags = tags, authors = authors.takeIf { it.isNotEmpty() })
  }

  override fun getSeriesMetadataFromBook(
    book: BookWithMedia,
    appendVolumeToTitle: Boolean,
  ): SeriesMetadataPatch? {
    val state = stateRepository.find(book.book.id, MetadataEnrichmentProcessor.AI_TITLE) ?: return null
    val title = state.resultJson?.let { objectMapper.readValue(it, MetadataEnrichmentAiResult::class.java).title } ?: return null
    if (bookRepository.findAllBySeriesId(book.book.seriesId).count { it.deletedDate == null } != 1) return null
    return SeriesMetadataPatch(
      status = null,
      title = title,
      titleSort = title,
      summary = null,
      readingDirection = null,
      publisher = null,
      ageRating = null,
      language = null,
      genres = null,
      totalBookCount = null,
      collections = emptySet(),
    )
  }

  override fun shouldLibraryHandlePatch(
    library: Library,
    target: MetadataPatchTarget,
  ): Boolean = target == MetadataPatchTarget.BOOK || target == MetadataPatchTarget.SERIES
}
