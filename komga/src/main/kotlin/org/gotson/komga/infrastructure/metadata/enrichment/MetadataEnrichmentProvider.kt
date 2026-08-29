package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.databind.ObjectMapper
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
  override val capabilities = setOf(BookMetadataPatchCapability.TITLE, BookMetadataPatchCapability.TAGS)
  override val supportsAppendVolume = false

  override fun getBookMetadataFromBook(book: BookWithMedia): BookMetadataPatch? {
    val states = stateRepository.findAllByBookId(book.book.id).associateBy { it.processor }
    if (states.isEmpty()) return null

    val title =
      states[MetadataEnrichmentProcessor.AI_TITLE]?.resultJson?.let {
        runCatching { objectMapper.readValue(it, MetadataEnrichmentAiResult::class.java).title }.getOrNull()
      }
    val tags = buildTags(states) ?: return if (title != null) BookMetadataPatch(title = title) else null
    return BookMetadataPatch(title = title, tags = tags)
  }

  override fun getSeriesMetadataFromBook(
    book: BookWithMedia,
    appendVolumeToTitle: Boolean,
  ): SeriesMetadataPatch? {
    val state = stateRepository.find(book.book.id, MetadataEnrichmentProcessor.AI_TITLE) ?: return null
    val title = state.resultJson?.let { runCatching { objectMapper.readValue(it, MetadataEnrichmentAiResult::class.java).title }.getOrNull() } ?: return null
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

  private fun buildTags(states: Map<MetadataEnrichmentProcessor, org.gotson.komga.domain.model.MetadataEnrichmentState>): Set<String>? {
    val translationState = states[MetadataEnrichmentProcessor.TAG_TRANSLATION] ?: return null
    val source = runCatching { objectMapper.readValue(translationState.inputJson, MetadataEnrichmentSource::class.java) }.getOrNull() ?: return null
    val translation = translationState.resultJson?.let { runCatching { objectMapper.readValue(it, MetadataEnrichmentTagResult::class.java) }.getOrNull() }

    val tags = linkedSetOf<String>()
    if (translation != null && translationState.resultRevision == translationState.revision && translation.exactTags.isNotEmpty()) {
      tags += translation.exactTags
    } else {
      source.tags.forEach { tag ->
        tags += translation?.mapping?.get(tag.key) ?: if (tag.normalizedType == "tag") tag.value else "${tag.normalizedType}_${tag.value}"
      }
    }
    states[MetadataEnrichmentProcessor.PAGE_SIZE]?.resultJson?.let {
      runCatching { objectMapper.readValue(it, MetadataEnrichmentBucketResult::class.java).label }.getOrNull()?.let(tags::add)
    }
    states[MetadataEnrichmentProcessor.TAG_SIZE]?.resultJson?.let {
      runCatching { objectMapper.readValue(it, MetadataEnrichmentBucketResult::class.java).label }.getOrNull()?.let(tags::add)
    }
    return tags
  }
}
