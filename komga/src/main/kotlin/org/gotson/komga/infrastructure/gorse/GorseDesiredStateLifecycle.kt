package org.gotson.komga.infrastructure.gorse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.Series
import org.gotson.komga.domain.persistence.BookMetadataAggregationRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.springframework.stereotype.Service
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val desiredStateLogger = KotlinLogging.logger {}
private val gorseUtcFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

@Service
class GorseDesiredStateLifecycle(
  private val dedupRepository: DedupRepository,
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
    dedupRepository.enqueueGorseSync(seriesId, libraryId, hidden)
  }

  fun reconcile(limit: Int = 100): Int {
    if (!gorseSettings.enabled) return 0
    var processed = 0
    repeat(limit) {
      val work = dedupRepository.findPendingGorseSync() ?: return processed
      try {
        applyChecked(work.seriesId, work.desiredHidden)
        check(dedupRepository.completeGorseSync(work.seriesId, work.desiredHidden, work.revision)) { "Gorse desired-state row changed before completion" }
      } catch (exception: Exception) {
        desiredStateLogger.error(exception) { "Gorse desired-state reconciliation failed for ${work.seriesId}" }
        dedupRepository.failGorseSync(work.seriesId, work.desiredHidden, work.revision, exception.message ?: exception.javaClass.simpleName)
      }
      processed++
    }
    return processed
  }

  fun syncNow(
    seriesId: String,
    fallbackLibraryId: String? = null,
  ): GorseSyncNowResult {
    if (!gorseSettings.enabled) return GorseSyncNowResult(seriesId, GorseSyncNowState.NOT_APPLICABLE, null, null)
    val series = seriesRepository.findByIdOrNull(seriesId)
    val libraryId = series?.libraryId ?: fallbackLibraryId ?: error("Cannot determine Library for Series $seriesId")
    val hidden = series == null || series.deletedDate != null || bookRepository.findAllBySeriesId(seriesId).none { it.deletedDate == null }
    val work = dedupRepository.enqueueGorseSync(seriesId, libraryId, hidden)
    return try {
      applyChecked(seriesId, hidden)
      check(dedupRepository.completeGorseSync(seriesId, hidden, work.revision)) { "Gorse desired-state row changed during synchronous confirmation" }
      GorseSyncNowResult(seriesId, GorseSyncNowState.CONFIRMED, hidden, null)
    } catch (exception: Exception) {
      dedupRepository.failGorseSync(seriesId, hidden, work.revision, exception.message ?: exception.javaClass.simpleName)
      GorseSyncNowResult(seriesId, GorseSyncNowState.FAILED, hidden, exception.message?.take(500) ?: exception.javaClass.simpleName)
    }
  }

  private fun applyChecked(
    seriesId: String,
    hidden: Boolean,
  ) {
    val series = seriesRepository.findByIdOrNull(seriesId)
    if (series == null) {
      check(hidden) { "Cannot restore a missing Komga Series in Gorse" }
      gorseClient.setHiddenChecked(seriesId, true)
    } else {
      gorseClient.upsertItemChecked(buildItem(series, hidden))
    }
    val observed = gorseClient.getItemChecked(seriesId)
    check(observed.IsHidden == hidden) { "Gorse Item $seriesId IsHidden=${observed.IsHidden}, expected $hidden" }
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

enum class GorseSyncNowState {
  CONFIRMED,
  NOT_APPLICABLE,
  FAILED,
}

data class GorseSyncNowResult(
  val seriesId: String,
  val state: GorseSyncNowState,
  val expectedHidden: Boolean?,
  val error: String?,
)
