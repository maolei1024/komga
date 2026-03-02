package org.gotson.komga.infrastructure.gorse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

@Component
class GorseEventListener(
  private val gorseClient: GorseClient,
  private val gorseSettings: GorseSettingsProvider,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val seriesRepository: SeriesRepository,
  private val bookRepository: BookRepository,
  private val userRepository: KomgaUserRepository,
  private val readProgressRepository: ReadProgressRepository,
) {
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

  private fun handleSeriesAdded(event: DomainEvent.SeriesAdded) {
    val series = event.series
    val metadata = seriesMetadataRepository.findByIdOrNull(series.id) ?: return
    val item = GorseItem(
      ItemId = series.id,
      Labels = (metadata.tags + metadata.genres).toList(),
      Categories = listOf(series.libraryId),
      Timestamp = series.createdDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
      Comment = metadata.title,
    )
    gorseClient.insertItem(item)
  }

  private fun handleSeriesUpdated(event: DomainEvent.SeriesUpdated) {
    val series = event.series
    val metadata = seriesMetadataRepository.findByIdOrNull(series.id) ?: return
    val item = GorseItem(
      ItemId = series.id,
      Labels = (metadata.tags + metadata.genres).toList(),
      Categories = listOf(series.libraryId),
      Timestamp = series.createdDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
      Comment = metadata.title,
    )
    gorseClient.updateItem(series.id, item)
  }

  private fun handleSeriesDeleted(event: DomainEvent.SeriesDeleted) {
    gorseClient.deleteItem(event.series.id)
  }

  private fun handleReadProgressChanged(event: DomainEvent.ReadProgressChanged) {
    val progress = event.progress
    if (!progress.completed) return

    val book = bookRepository.findByIdOrNull(progress.bookId) ?: return
    val feedback = GorseFeedback(
      FeedbackType = gorseSettings.feedbackType,
      UserId = progress.userId,
      ItemId = book.seriesId,
      Timestamp = progress.readDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
    )
    gorseClient.insertFeedback(listOf(feedback))
  }

  /**
   * 批量同步所有 Series 到 Gorse Items
   */
  fun syncAllItems(): Int {
    val allSeries = seriesRepository.findAll()
    val items = allSeries.mapNotNull { series ->
      val metadata = seriesMetadataRepository.findByIdOrNull(series.id) ?: return@mapNotNull null
      GorseItem(
        ItemId = series.id,
        Labels = (metadata.tags + metadata.genres).toList(),
        Categories = listOf(series.libraryId),
        Timestamp = series.createdDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        Comment = metadata.title,
      )
    }
    if (items.isNotEmpty()) {
      // 分批发送，每批 100 个
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
    val completedProgress = allProgress.filter { it.completed }
    val feedbackList = completedProgress.mapNotNull { progress ->
      val book = bookRepository.findByIdOrNull(progress.bookId) ?: return@mapNotNull null
      GorseFeedback(
        FeedbackType = gorseSettings.feedbackType,
        UserId = progress.userId,
        ItemId = book.seriesId,
        Timestamp = progress.readDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
      )
    }
    if (feedbackList.isNotEmpty()) {
      // 分批发送，每批 100 个
      feedbackList.chunked(100).forEach { chunk ->
        gorseClient.insertFeedback(chunk)
      }
    }
    logger.info { "Gorse: synced ${feedbackList.size} feedback entries" }
    return feedbackList.size
  }
}
