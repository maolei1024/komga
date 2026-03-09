package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
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
import java.time.Instant

private val logger = KotlinLogging.logger {}

@RestController
@RequestMapping("api/v1/gorse/feedback", produces = [MediaType.APPLICATION_JSON_VALUE])
class GorseFeedbackController(
  private val gorseClient: GorseClient,
  private val gorseSettings: GorseSettingsProvider,
) {
  @GetMapping("like/{itemId}")
  @Operation(summary = "Check if current user liked an item")
  fun getLikeStatus(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable itemId: String,
  ): Map<String, Boolean> {
    if (!gorseSettings.enabled) {
      throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gorse is not enabled")
    }

    val userId = principal.user.id
    val feedbacks = gorseClient.getUserFeedbackByType(userId, "like")
    val isLiked = feedbacks.any { it.ItemId == itemId }
    logger.debug { "Gorse: user $userId like status for item $itemId: $isLiked" }
    return mapOf("isLiked" to isLiked)
  }

  @PutMapping("like/{itemId}")
  @Operation(summary = "Like an item")
  fun likeItem(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable itemId: String,
  ): Map<String, Boolean> {
    if (!gorseSettings.enabled) {
      throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gorse is not enabled")
    }

    val userId = principal.user.id
    val feedback =
      GorseFeedback(
        FeedbackType = "like",
        UserId = userId,
        ItemId = itemId,
        Timestamp = Instant.now().toString(),
      )
    gorseClient.insertFeedback(listOf(feedback))
    logger.info { "Gorse: user $userId liked item $itemId" }
    return mapOf("isLiked" to true)
  }

  @DeleteMapping("like/{itemId}")
  @Operation(summary = "Unlike an item")
  fun unlikeItem(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable itemId: String,
  ): Map<String, Boolean> {
    if (!gorseSettings.enabled) {
      throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gorse is not enabled")
    }

    val userId = principal.user.id
    gorseClient.deleteFeedback("like", userId, itemId)
    logger.info { "Gorse: user $userId unliked item $itemId" }
    return mapOf("isLiked" to false)
  }
}
