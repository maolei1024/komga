package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.infrastructure.gorse.GorseClient
import org.gotson.komga.infrastructure.gorse.GorseFeedback
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("api/v1/gorse/like", produces = [MediaType.APPLICATION_JSON_VALUE])
class GorseLikeController(
  private val gorseClient: GorseClient,
  private val gorseSettings: GorseSettingsProvider,
  private val bookRepository: BookRepository,
) {
  companion object {
    private const val FEEDBACK_TYPE = "like"
    private val ISO_UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
  }

  @GetMapping("{seriesId}")
  @Operation(summary = "Check if user liked a series")
  fun getLikeStatus(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable seriesId: String,
  ): Map<String, Boolean> {
    if (!gorseSettings.enabled) return mapOf("liked" to false)

    val userId = principal.user.id
    val feedbacks = gorseClient.getUserFeedbackByType(userId, FEEDBACK_TYPE)
    val liked = feedbacks.any { it.ItemId == seriesId }
    logger.debug { "Gorse: user $userId like status for series $seriesId: $liked" }
    return mapOf("liked" to liked)
  }

  @PutMapping("{seriesId}")
  @Operation(summary = "Like a series")
  fun likeSeries(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable seriesId: String,
  ): Map<String, Boolean> {
    if (!gorseSettings.enabled) return mapOf("success" to false)

    val userId = principal.user.id
    val feedback = GorseFeedback(
      FeedbackType = FEEDBACK_TYPE,
      UserId = userId,
      ItemId = seriesId,
      Timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
    )
    gorseClient.insertFeedback(listOf(feedback))
    logger.info { "Gorse: user $userId liked series $seriesId" }
    return mapOf("success" to true)
  }

  @DeleteMapping("{seriesId}")
  @Operation(summary = "Unlike a series")
  fun unlikeSeries(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable seriesId: String,
  ): Map<String, Boolean> {
    if (!gorseSettings.enabled) return mapOf("success" to false)

    val userId = principal.user.id
    gorseClient.deleteFeedback(FEEDBACK_TYPE, userId, seriesId)
    logger.info { "Gorse: user $userId unliked series $seriesId" }
    return mapOf("success" to true)
  }

  @GetMapping("book/{bookId}")
  @Operation(summary = "Check if user liked the series of a book")
  fun getLikeStatusByBook(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable bookId: String,
  ): Map<String, Any> {
    if (!gorseSettings.enabled) return mapOf("liked" to false, "seriesId" to "")

    val book = bookRepository.findByIdOrNull(bookId)
      ?: return mapOf("liked" to false, "seriesId" to "")

    val userId = principal.user.id
    val feedbacks = gorseClient.getUserFeedbackByType(userId, FEEDBACK_TYPE)
    val liked = feedbacks.any { it.ItemId == book.seriesId }
    logger.debug { "Gorse: user $userId like status for book $bookId (series ${book.seriesId}): $liked" }
    return mapOf("liked" to liked, "seriesId" to book.seriesId)
  }
}
