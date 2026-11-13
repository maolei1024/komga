package org.gotson.komga.infrastructure.gorse

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.BookMetadata
import org.gotson.komga.domain.model.ContentRestrictions
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.MetadataEnrichmentBucket
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentState
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentBuckets
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSettingsProvider
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSource
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSourceExtractor
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentTagResult
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
class GorseLabelBuilder(
  private val books: BookRepository,
  private val media: MediaRepository,
  private val states: MetadataEnrichmentStateRepository,
  private val seriesMetadata: SeriesMetadataRepository,
  private val metadata: BookMetadataRepository,
  private val settings: MetadataEnrichmentSettingsProvider,
  private val mapper: ObjectMapper,
) {
  private val coverageCache = ConcurrentHashMap<CoverageKey, CachedCoverage>()

  @EventListener
  fun invalidateCoverage(event: DomainEvent) {
    when (event) {
      is DomainEvent.BookAdded,
      is DomainEvent.BookUpdated,
      is DomainEvent.BookDeleted,
      is DomainEvent.SeriesUpdated,
      is DomainEvent.SeriesDeleted,
      is DomainEvent.LibraryDeleted,
      -> coverageCache.clear()
      else -> Unit
    }
  }

  fun build(seriesId: String): Map<String, Any> {
    val active = books.findAllBySeriesId(seriesId).filter { it.deletedDate == null }
    val inputs =
      active.map { book ->
        input(states.find(book.id, MetadataEnrichmentProcessor.METADATA), metadata.findByIdOrNull(book.id), media.findByIdOrNull(book.id)?.pageCount ?: 0)
      }
    val typed = inputs.mapNotNull { it.source }.flatMap { it.tags }.distinctBy { it.key }

    fun values(type: String) =
      typed
        .filter { it.normalizedType == type }
        .map { it.normalizedValue }
        .distinct()
        .sorted()
    val authors = inputs.flatMap { it.authors }.distinct().sorted()
    val fields = seriesMetadata.findByIdOrNull(seriesId)
    val genres = if (fields?.genresLock == true) fields.genres.toList() else values("genre") + fields?.genres.orEmpty()
    return mapOf(
      "tags" to inputs.flatMap { it.tags }.distinct().sorted(),
      "authors" to authors,
      "groups" to values("group"),
      "characters" to values("character"),
      "genres" to genres.map { it.lowercase() }.distinct().sorted(),
      "preference_buckets" to buckets(inputs),
    ).filterValues { it.isNotEmpty() }
  }

  fun coverage(user: KomgaUser): Map<Pair<String, String>, Int> {
    val key = CoverageKey(user.id, user.getAuthorizedLibraryIds(null)?.toSet(), user.restrictions, settings.pageSizeBuckets, settings.tagSizeBuckets)
    return coverageCache
      .compute(key) { _, previous ->
        if (previous != null && previous.expiresAt > System.nanoTime()) {
          previous
        } else {
          val counts = computeCoverage(user)
          CachedCoverage(System.nanoTime() + TimeUnit.SECONDS.toNanos(15), counts)
        }
      }!!
      .counts
  }

  private fun computeCoverage(user: KomgaUser): Map<Pair<String, String>, Int> {
    val active = books.findAll().filter { it.deletedDate == null && user.canAccessLibrary(it.libraryId) }
    val bookIds = active.map { it.id }
    val fields = metadata.findAllByIds(bookIds).associateBy { it.bookId }
    val pages = media.getPagesSizes(bookIds).toMap()
    val sourceStates = states.findAllByProcessor(MetadataEnrichmentProcessor.METADATA).associateBy { it.bookId }
    return active
      .groupBy { it.seriesId }
      .mapNotNull { (seriesId, group) ->
        val series = if (user.restrictions.isRestricted) seriesMetadata.findByIdOrNull(seriesId) else null
        if (!user.isContentAllowed(series?.ageRating, series?.sharingLabels.orEmpty()))
          null
        else
          buckets(group.map { input(sourceStates[it.id], fields[it.id], pages[it.id] ?: 0) })
            .takeIf { it.size == 2 }
            ?.let { it[0] to it[1] }
      }.groupingBy { it }
      .eachCount()
  }

  private fun input(
    state: MetadataEnrichmentState?,
    fields: BookMetadata?,
    pages: Int,
  ): LabelInput {
    val source = state?.let { runCatching { mapper.readValue(it.inputJson, MetadataEnrichmentSource::class.java) }.getOrNull() }
    val result = state?.resultJson?.let { runCatching { mapper.readValue(it, MetadataEnrichmentTagResult::class.java) }.getOrNull() }
    val raw = source?.tags.orEmpty().filter { it.normalizedType == "tag" }
    val tags =
      if (fields?.tagsLock == true) {
        val stable = raw.associate { (result?.mapping?.get(it.key) ?: it.value).lowercase() to it.normalizedValue }
        val excluded =
          source
            ?.unclassifiedTags
            .orEmpty()
            .map { it.lowercase() }
            .toSet() +
            source
              ?.tags
              .orEmpty()
              .filter { it.normalizedType != "tag" }
              .flatMap {
                listOf(
                  it.normalizedValue,
                  result
                    ?.mapping
                    ?.get(it.key)
                    .orEmpty()
                    .lowercase(),
                )
              }
        fields.tags
          .filterNot { tag -> MetadataEnrichmentSourceExtractor.technicalTag(tag) || tag in excluded }
          .map { stable[it] ?: it }
      } else {
        raw.map { it.normalizedValue }
      }
    val rawAuthors = source?.tags.orEmpty().filter { it.normalizedType == "artist" }
    val authors =
      if (fields?.authorsLock == true) {
        val stable = rawAuthors.associate { (result?.mapping?.get(it.key) ?: it.value).lowercase() to it.normalizedValue }
        fields.authors.flatMap { author -> stable[author.name.trim().lowercase()]?.let { listOf(it) } ?: author.name.split(" & ") }
      } else {
        rawAuthors.map { it.value }.ifEmpty { fields?.authors.orEmpty().flatMap { it.name.split(" & ") } }
      }
    val currentTags =
      (fields?.tags ?: source?.fallbackTags ?: tags.toSet())
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() && !MetadataEnrichmentSourceExtractor.technicalTag(it) }
        .distinct()
    return LabelInput(source, pages, tags.distinct(), currentTags, authors.map { it.trim().lowercase() }.filter { it.isNotBlank() && it != "unknown" }.distinct())
  }

  private fun buckets(inputs: List<LabelInput>): List<String> =
    buildList {
      val pages = inputs.sumOf { it.pages }
      if (pages > 0) add(MetadataEnrichmentBuckets.label(pages, settings.pageSizeBuckets))
      if (inputs.isNotEmpty() && inputs.all { it.source?.contentTagsKnown == true }) {
        add(MetadataEnrichmentBuckets.label(inputs.flatMap { it.tags }.distinct().size, settings.tagSizeBuckets))
      } else if (inputs.isNotEmpty() && inputs.all { it.source?.contentTagsKnown == true || it.source?.fallbackTags != null }) {
        add(MetadataEnrichmentBuckets.label(inputs.flatMap { it.currentTags }.distinct().size, settings.tagSizeBuckets))
      }
    }

  private data class LabelInput(
    val source: MetadataEnrichmentSource?,
    val pages: Int,
    val tags: List<String>,
    val currentTags: List<String>,
    val authors: List<String>,
  )

  private data class CoverageKey(
    val userId: String,
    val libraries: Set<String>?,
    val restrictions: ContentRestrictions,
    val pages: List<MetadataEnrichmentBucket>,
    val tags: List<MetadataEnrichmentBucket>,
  )

  private data class CachedCoverage(
    val expiresAt: Long,
    val counts: Map<Pair<String, String>, Int>,
  )
}
