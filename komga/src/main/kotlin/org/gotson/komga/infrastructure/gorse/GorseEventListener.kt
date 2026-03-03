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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

private val ISO_UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

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

  private fun buildLabels(tags: Set<String>, genres: Set<String>): Map<String, Any> {
    val labels = mutableMapOf<String, Any>()
    if (tags.isNotEmpty()) labels["tags"] = tags.toList()
    if (genres.isNotEmpty()) labels["genres"] = genres.toList()
    return labels
  }

  private fun handleSeriesAdded(event: DomainEvent.SeriesAdded) {
    val series = event.series
    val metadata = seriesMetadataRepository.findByIdOrNull(series.id) ?: return
    val item = GorseItem(
      ItemId = series.id,
      Labels = buildLabels(metadata.tags, metadata.genres),
      Categories = listOf(series.libraryId),
      Timestamp = series.createdDate.atOffset(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
      Comment = metadata.title,
    )
    gorseClient.insertItem(item)
  }

  private fun handleSeriesUpdated(event: DomainEvent.SeriesUpdated) {
    val series = event.series
    val metadata = seriesMetadataRepository.findByIdOrNull(series.id) ?: return
    val item = GorseItem(
      ItemId = series.id,
      Labels = buildLabels(metadata.tags, metadata.genres),
      Categories = listOf(series.libraryId),
      Timestamp = series.createdDate.atOffset(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
      Comment = metadata.title,
    )
    gorseClient.updateItem(series.id, item)
  }

  private fun handleSeriesDeleted(event: DomainEvent.SeriesDeleted) {
    gorseClient.deleteItem(event.series.id)
  }

  private fun handleReadProgressChanged(event: DomainEvent.ReadProgressChanged) {
    val progress = event.progress
    logger.info { "Gorse: ReadProgressChanged - bookId=${progress.bookId}, userId=${progress.userId}, completed=${progress.completed}, page=${progress.page}" }
    if (!progress.completed) return

    val book = bookRepository.findByIdOrNull(progress.bookId)
    if (book == null) {
      logger.warn { "Gorse: book not found for bookId=${progress.bookId}" }
      return
    }
    logger.info { "Gorse: sending feedback for series=${book.seriesId}, user=${progress.userId}, type=${gorseSettings.feedbackType}" }
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
    val items = allSeries.mapNotNull { series ->
      val metadata = seriesMetadataRepository.findByIdOrNull(series.id) ?: return@mapNotNull null
      GorseItem(
        ItemId = series.id,
        Labels = buildLabels(metadata.tags, metadata.genres),
        Categories = listOf(series.libraryId),
        Timestamp = series.createdDate.atOffset(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
        Comment = metadata.title,
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
    val completedProgress = allProgress.filter { it.completed }
    val feedbackList = completedProgress.mapNotNull { progress ->
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
    logger.info { "Gorse: synced ${feedbackList.size} feedback entries" }
    return feedbackList.size
  }
}
