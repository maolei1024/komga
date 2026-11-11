package org.gotson.komga.infrastructure.gorse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
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
  private val seriesRepository: SeriesRepository,
  private val bookRepository: BookRepository,
  private val userRepository: KomgaUserRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val mediaRepository: MediaRepository,
  private val desiredStateLifecycle: GorseDesiredStateLifecycle,
) {
  // 去重：同一 bookId:userId 运行期间只发送一次 read 反馈
  private val sentReadFeedback = ConcurrentHashMap<String, Boolean>()

  @EventListener
  fun handleEvent(event: DomainEvent) {
    if (!gorseSettings.enabled) return
    try {
      when (event) {
        is DomainEvent.SeriesAdded -> enqueueDesiredState(event.series.id, event.series.libraryId)
        is DomainEvent.SeriesUpdated -> enqueueDesiredState(event.series.id, event.series.libraryId)
        is DomainEvent.SeriesDeleted -> enqueueDesiredState(event.series.id, event.series.libraryId)
        is DomainEvent.ReadProgressChanged -> handleReadProgressChanged(event)
        is DomainEvent.BookAdded -> enqueueDesiredState(event.book.seriesId, event.book.libraryId)
        is DomainEvent.BookUpdated -> enqueueDesiredState(event.book.seriesId, event.book.libraryId)
        is DomainEvent.BookDeleted -> enqueueDesiredState(event.book.seriesId, event.book.libraryId)
        else -> Unit
      }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: error handling event $event" }
    }
  }

  private fun enqueueDesiredState(
    seriesId: String,
    libraryId: String,
  ) {
    desiredStateLifecycle.enqueue(seriesId, libraryId)
    desiredStateLifecycle.reconcile(1)
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
    val feedback =
      GorseFeedback(
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
    allSeries.forEach { desiredStateLifecycle.enqueue(it.id, it.libraryId) }
    desiredStateLifecycle.reconcile(allSeries.size)
    logger.info { "Gorse: queued desired state for ${allSeries.size} items" }
    return allSeries.size
  }

  /**
   * 批量同步所有用户到 Gorse Users
   */
  fun syncAllUsers(): Int {
    val allUsers = userRepository.findAll()
    val users =
      allUsers.map { user ->
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
    val feedbackList =
      allProgress.mapNotNull { progress ->
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
