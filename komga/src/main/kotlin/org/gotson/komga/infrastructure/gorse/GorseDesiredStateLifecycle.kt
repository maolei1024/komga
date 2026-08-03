package org.gotson.komga.infrastructure.gorse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.persistence.BookMetadataAggregationRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupDecisionRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.springframework.stereotype.Service
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val desiredStateLogger = KotlinLogging.logger {}
private val gorseUtcFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

@Service
class GorseDesiredStateLifecycle(
  private val decisionRepository: DedupDecisionRepository,
  private val seriesRepository: SeriesRepository,
  private val bookRepository: BookRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookMetadataAggregationRepository: BookMetadataAggregationRepository,
  private val gorseSettings: GorseSettingsProvider,
  private val gorseClient: GorseClient,
) {
  fun enqueue(
    seriesId: String,
    fallbackLibraryId: String? = null,
  ) {
    val series = seriesRepository.findByIdOrNull(seriesId)
    val libraryId = series?.libraryId ?: fallbackLibraryId ?: return
    val hidden = series == null || series.deletedDate != null || bookRepository.findAllBySeriesId(seriesId).none { it.deletedDate == null }
    decisionRepository.enqueueGorseSync(seriesId, libraryId, hidden)
  }

  fun reconcile(limit: Int = 100): Int {
    if (!gorseSettings.enabled) return 0
    var processed = 0
    repeat(limit) {
      val work = decisionRepository.findPendingGorseSync() ?: return processed
      try {
        val series = seriesRepository.findByIdOrNull(work.seriesId)
        if (series == null) {
          check(work.desiredHidden) { "Cannot restore a missing Komga Series in Gorse" }
          gorseClient.setHiddenChecked(work.seriesId, true)
        } else {
          gorseClient.upsertItemChecked(buildItem(series, work.desiredHidden))
        }
        check(decisionRepository.completeGorseSync(work.seriesId)) { "Gorse desired-state row changed before completion" }
      } catch (exception: Exception) {
        desiredStateLogger.error(exception) { "Gorse desired-state reconciliation failed for ${work.seriesId}" }
        decisionRepository.failGorseSync(work.seriesId, exception.message ?: exception.javaClass.simpleName)
      }
      processed++
    }
    return processed
  }

  fun buildItem(series: Series): GorseItem =
    buildItem(
      series,
      series.deletedDate != null || bookRepository.findAllBySeriesId(series.id).none { it.deletedDate == null },
    )

  private fun buildItem(
    series: Series,
    hidden: Boolean,
  ): GorseItem =
    GorseItem(
      ItemId = series.id,
      IsHidden = hidden,
      Labels = buildLabels(series.id),
      Categories = listOf(series.libraryId),
      Timestamp = series.createdDate.atOffset(ZoneOffset.UTC).format(gorseUtcFormatter),
      Comment = seriesMetadataRepository.findByIdOrNull(series.id)?.title ?: "",
    )

  private fun buildLabels(seriesId: String): Map<String, Any> {
    val labels = mutableMapOf<String, Any>()
    val metadata = seriesMetadataRepository.findByIdOrNull(seriesId)
    if (metadata != null && metadata.genres.isNotEmpty()) labels["genres"] = metadata.genres.toList()
    val aggregation = bookMetadataAggregationRepository.findByIdOrNull(seriesId)
    if (aggregation != null) {
      if (aggregation.authors.isNotEmpty()) labels["authors"] = aggregation.authors.map { it.name }.distinct()
      val tags =
        buildSet {
          metadata?.let { addAll(it.tags) }
          addAll(aggregation.tags)
        }
      if (tags.isNotEmpty()) labels["tags"] = tags.toList()
    } else if (metadata != null && metadata.tags.isNotEmpty()) {
      labels["tags"] = metadata.tags.toList()
    }
    return labels
  }
}
