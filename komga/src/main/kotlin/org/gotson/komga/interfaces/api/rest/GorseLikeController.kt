package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.infrastructure.gorse.GorseClient
import org.gotson.komga.infrastructure.gorse.GorseFeedback
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
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
    private val ISO_UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
  }

  private fun getFeedbackType(): String = gorseSettings.positiveFeedbackType

  // ===== 需要认证的端点 =====

  @GetMapping("{seriesId}")
  @Operation(summary = "Check if user liked a series")
  fun getLikeStatus(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable seriesId: String,
  ): Map<String, Boolean> {
    if (!gorseSettings.enabled) return mapOf("liked" to false)
    val userId = principal.user.id
    return checkLikeStatus(userId, seriesId)
  }

  @PutMapping("{seriesId}")
  @Operation(summary = "Like a series")
  fun likeSeries(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable seriesId: String,
  ): Map<String, Boolean> {
    if (!gorseSettings.enabled) return mapOf("success" to false)
    val userId = principal.user.id
    return doLike(userId, seriesId)
  }

  @DeleteMapping("{seriesId}")
  @Operation(summary = "Unlike a series")
  fun unlikeSeries(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable seriesId: String,
  ): Map<String, Boolean> {
    if (!gorseSettings.enabled) return mapOf("success" to false)
    val userId = principal.user.id
    return doUnlike(userId, seriesId)
  }

  @GetMapping("book/{bookId}")
  @Operation(summary = "Check if user liked the series of a book")
  fun getLikeStatusByBook(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable bookId: String,
  ): Map<String, Any> {
    if (!gorseSettings.enabled) return mapOf("liked" to false, "seriesId" to "")
    val userId = principal.user.id
    return checkLikeStatusByBook(userId, bookId)
  }

  // ===== 匿名端点（使用配置的 anonymousUserId） =====

  @GetMapping("anonymous/{seriesId}")
  @Operation(summary = "Check if anonymous user liked a series")
  fun getAnonymousLikeStatus(
    @PathVariable seriesId: String,
  ): Map<String, Boolean> {
    val userId = getAnonymousUserId()
    return checkLikeStatus(userId, seriesId)
  }

  @PutMapping("anonymous/{seriesId}")
  @Operation(summary = "Like a series as anonymous user")
  fun anonymousLikeSeries(
    @PathVariable seriesId: String,
  ): Map<String, Boolean> {
    val userId = getAnonymousUserId()
    return doLike(userId, seriesId)
  }

  @DeleteMapping("anonymous/{seriesId}")
  @Operation(summary = "Unlike a series as anonymous user")
  fun anonymousUnlikeSeries(
    @PathVariable seriesId: String,
  ): Map<String, Boolean> {
    val userId = getAnonymousUserId()
    return doUnlike(userId, seriesId)
  }

  @GetMapping("anonymous/book/{bookId}")
  @Operation(summary = "Check if anonymous user liked the series of a book")
  fun getAnonymousLikeStatusByBook(
    @PathVariable bookId: String,
  ): Map<String, Any> {
    val userId = getAnonymousUserId()
    return checkLikeStatusByBook(userId, bookId)
  }

  // ===== 公共逻辑 =====

  private fun getAnonymousUserId(): String {
    if (!gorseSettings.enabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gorse 未启用")
    val userId = gorseSettings.anonymousUserId
    if (userId.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "匿名用户 ID 未配置，请在 Gorse 设置中配置")
    return userId
  }

  private fun checkLikeStatus(userId: String, seriesId: String): Map<String, Boolean> {
    val feedbacks = gorseClient.getUserFeedbackByType(userId, getFeedbackType())
    val liked = feedbacks.any { it.ItemId == seriesId }
    logger.debug { "Gorse: user $userId like status for series $seriesId: $liked" }
    return mapOf("liked" to liked)
  }

  private fun checkLikeStatusByBook(userId: String, bookId: String): Map<String, Any> {
    val book = bookRepository.findByIdOrNull(bookId)
      ?: return mapOf("liked" to false, "seriesId" to "")
    val feedbacks = gorseClient.getUserFeedbackByType(userId, getFeedbackType())
    val liked = feedbacks.any { it.ItemId == book.seriesId }
    logger.debug { "Gorse: user $userId like status for book $bookId (series ${book.seriesId}): $liked" }
    return mapOf("liked" to liked, "seriesId" to book.seriesId)
  }

  private fun doLike(userId: String, seriesId: String): Map<String, Boolean> {
    val feedback = GorseFeedback(
      FeedbackType = getFeedbackType(),
      UserId = userId,
      ItemId = seriesId,
      Timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
    )
    gorseClient.insertFeedback(listOf(feedback))
    logger.info { "Gorse: user $userId liked series $seriesId" }
    return mapOf("success" to true)
  }

  private fun doUnlike(userId: String, seriesId: String): Map<String, Boolean> {
    gorseClient.deleteFeedback(getFeedbackType(), userId, seriesId)
    logger.info { "Gorse: user $userId unliked series $seriesId" }
    return mapOf("success" to true)
  }
}
