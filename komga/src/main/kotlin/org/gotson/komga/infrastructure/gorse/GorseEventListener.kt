package org.gotson.komga.infrastructure.gorse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.persistence.BookMetadataAggregationRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private val ISO_UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

@Component
class GorseEventListener(
  private val gorseClient: GorseClient,
  private val gorseSettings: GorseSettingsProvider,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val bookMetadataAggregationRepository: BookMetadataAggregationRepository,
  private val seriesRepository: SeriesRepository,
  private val bookRepository: BookRepository,
  private val userRepository: KomgaUserRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val mediaRepository: MediaRepository,
) {
  // 去重：同一 bookId:userId 运行期间只发送一次 read 反馈
  private val sentReadFeedback = ConcurrentHashMap<String, Boolean>()
  @EventListener
  fun handleEvent(event: DomainEvent) {
    if (!gorseSettings.enabled) return
    try {
      when (event) {
        is DomainEvent.SeriesAdded -> handleSeriesAdded(event)
        is DomainEvent.SeriesUpdated -> handleSeriesUpdated(event)
        is DomainEvent.SeriesDeleted -> handleSeriesDeleted(event)
        is DomainEvent.ReadProgressChanged -> handleReadProgressChanged(event)
        else -> Unit
      }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: error handling event $event" }
    }
  }

  /**
   * 构建 Gorse Labels，合并 SeriesMetadata (genres, tags) 和
   * BookMetadataAggregation (authors, tags) 的信息
   */
  private fun buildLabelsForSeries(seriesId: String): Map<String, Any> {
    val labels = mutableMapOf<String, Any>()

    val metadata = seriesMetadataRepository.findByIdOrNull(seriesId)
    if (metadata != null) {
      if (metadata.genres.isNotEmpty()) labels["genres"] = metadata.genres.toList()
    }

    val aggregation = bookMetadataAggregationRepository.findByIdOrNull(seriesId)
    if (aggregation != null) {
      if (aggregation.authors.isNotEmpty()) {
        labels["authors"] = aggregation.authors.map { it.name }.distinct()
      }
      val allTags = mutableSetOf<String>()
      if (metadata != null) allTags.addAll(metadata.tags)
      allTags.addAll(aggregation.tags)
      if (allTags.isNotEmpty()) labels["tags"] = allTags.toList()
    } else if (metadata != null && metadata.tags.isNotEmpty()) {
      labels["tags"] = metadata.tags.toList()
    }

    return labels
  }

  private fun getSeriesTitle(seriesId: String): String {
    return seriesMetadataRepository.findByIdOrNull(seriesId)?.title ?: ""
  }

  private fun handleSeriesAdded(event: DomainEvent.SeriesAdded) {
    val series = event.series
    val item = GorseItem(
      ItemId = series.id,
      Labels = buildLabelsForSeries(series.id),
      Categories = listOf(series.libraryId),
      Timestamp = series.createdDate.atOffset(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
      Comment = getSeriesTitle(series.id),
    )
    logger.info { "Gorse: inserting item ${series.id}, labels=${item.Labels}" }
    gorseClient.insertItem(item)
  }

  private fun handleSeriesUpdated(event: DomainEvent.SeriesUpdated) {
    val series = event.series
    val item = GorseItem(
      ItemId = series.id,
      Labels = buildLabelsForSeries(series.id),
      Categories = listOf(series.libraryId),
      Timestamp = series.createdDate.atOffset(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
      Comment = getSeriesTitle(series.id),
    )
    logger.info { "Gorse: updating item ${series.id}, labels=${item.Labels}" }
    gorseClient.updateItem(series.id, item)
  }

  private fun handleSeriesDeleted(event: DomainEvent.SeriesDeleted) {
    gorseClient.deleteItem(event.series.id)
  }

  private fun handleReadProgressChanged(event: DomainEvent.ReadProgressChanged) {
    val progress = event.progress
    val media = mediaRepository.findByIdOrNull(progress.bookId)
    if (media == null || media.pageCount == 0) {
      logger.warn { "Gorse: media not found or empty for bookId=${progress.bookId}" }
      return
    }

    val readRatio = progress.page.toDouble() / media.pageCount
    logger.info { "Gorse: ReadProgressChanged - bookId=${progress.bookId}, userId=${progress.userId}, page=${progress.page}/${media.pageCount}, ratio=${String.format("%.0f%%", readRatio * 100)}, threshold=${String.format("%.0f%%", gorseSettings.readThreshold * 100)}" }

    if (readRatio < gorseSettings.readThreshold) return

    // 去重：同一 book+user 只发送一次
    val feedbackKey = "${progress.bookId}:${progress.userId}"
    if (sentReadFeedback.putIfAbsent(feedbackKey, true) != null) {
      logger.info { "Gorse: feedback already sent for $feedbackKey, skipping" }
      return
    }

    val book = bookRepository.findByIdOrNull(progress.bookId)
    if (book == null) {
      logger.warn { "Gorse: book not found for bookId=${progress.bookId}" }
      return
    }
    logger.info { "Gorse: sending feedback (threshold reached) for series=${book.seriesId}, user=${progress.userId}, type=${gorseSettings.feedbackType}" }
    val feedback = GorseFeedback(
      FeedbackType = gorseSettings.feedbackType,
      UserId = progress.userId,
      ItemId = book.seriesId,
      Timestamp = progress.readDate.atOffset(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
    )
    gorseClient.insertFeedback(listOf(feedback))
  }

  /**
   * 批量同步所有 Series 到 Gorse Items
   */
  fun syncAllItems(): Int {
    val allSeries = seriesRepository.findAll()
    val items = allSeries.map { series ->
      GorseItem(
        ItemId = series.id,
        Labels = buildLabelsForSeries(series.id),
        Categories = listOf(series.libraryId),
        Timestamp = series.createdDate.atOffset(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
        Comment = getSeriesTitle(series.id),
      )
    }
    if (items.isNotEmpty()) {
      items.chunked(100).forEach { chunk ->
        gorseClient.insertItems(chunk)
      }
    }
    logger.info { "Gorse: synced ${items.size} items" }
    return items.size
  }

  /**
   * 批量同步所有用户到 Gorse Users
   */
  fun syncAllUsers(): Int {
    val allUsers = userRepository.findAll()
    val users = allUsers.map { user ->
      GorseUser(
        UserId = user.id,
        Comment = user.email,
      )
    }
    if (users.isNotEmpty()) {
      gorseClient.insertUsers(users.toList())
    }
    logger.info { "Gorse: synced ${users.size} users" }
    return users.size
  }

  /**
   * 批量同步所有已完成的阅读进度到 Gorse Feedback
   */
  fun syncAllFeedback(): Int {
    val allProgress = readProgressRepository.findAll()
    val feedbackList = allProgress.mapNotNull { progress ->
      val media = mediaRepository.findByIdOrNull(progress.bookId) ?: return@mapNotNull null
      if (media.pageCount == 0) return@mapNotNull null
      val readRatio = progress.page.toDouble() / media.pageCount
      if (readRatio < gorseSettings.readThreshold) return@mapNotNull null

      val book = bookRepository.findByIdOrNull(progress.bookId) ?: return@mapNotNull null
      GorseFeedback(
        FeedbackType = gorseSettings.feedbackType,
        UserId = progress.userId,
        ItemId = book.seriesId,
        Timestamp = progress.readDate.atOffset(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
      )
    }
    if (feedbackList.isNotEmpty()) {
      feedbackList.chunked(100).forEach { chunk ->
        gorseClient.insertFeedback(chunk)
      }
    }
    logger.info { "Gorse: synced ${feedbackList.size} feedback entries (threshold=${String.format("%.0f%%", gorseSettings.readThreshold * 100)})" }
    return feedbackList.size
  }
}
