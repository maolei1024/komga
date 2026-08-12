package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.gotson.komga.infrastructure.gorse.GorseEventListener
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.interfaces.api.rest.dto.GorseSettingsDto
import org.gotson.komga.interfaces.api.rest.dto.GorseSettingsUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.GorseSyncResultDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(value = ["api/v1/gorse"], produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "gorse")
class GorseController(
  private val gorseSettings: GorseSettingsProvider,
  private val gorseEventListener: GorseEventListener,
) {
  @GetMapping
  @Operation(summary = "Retrieve Gorse settings")
  fun getGorseSettings(): GorseSettingsDto =
    GorseSettingsDto(
      enabled = gorseSettings.enabled,
      apiUrl = gorseSettings.apiUrl,
      apiKey = gorseSettings.apiKey,
      feedbackType = gorseSettings.feedbackType,
      positiveFeedbackType = gorseSettings.positiveFeedbackType,
      negativeFeedbackType = gorseSettings.negativeFeedbackType,
      anonymousUserId = gorseSettings.anonymousUserId,
      readThreshold = gorseSettings.readThreshold,
      tagPenaltyExponent = gorseSettings.tagPenaltyExponent,
    )

  @PatchMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Update Gorse settings")
  fun updateGorseSettings(
    @Valid @RequestBody newSettings: GorseSettingsUpdateDto,
  ) {
    val feedbackType = (newSettings.feedbackType ?: gorseSettings.feedbackType).trim()
    val positiveFeedbackType = (newSettings.positiveFeedbackType ?: gorseSettings.positiveFeedbackType).trim()
    val negativeFeedbackType = (newSettings.negativeFeedbackType ?: gorseSettings.negativeFeedbackType).trim()
    val feedbackTypes = listOf(feedbackType, positiveFeedbackType, negativeFeedbackType)
    if (feedbackTypes.any { it.isBlank() } || feedbackTypes.distinct().size != feedbackTypes.size) {
      throw org.springframework.web.server.ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "Read, positive, and negative feedback types must be non-blank and pairwise distinct",
      )
    }

    newSettings.enabled?.let { gorseSettings.enabled = it }
    newSettings.apiUrl?.let { gorseSettings.apiUrl = it }
    newSettings.apiKey?.let { gorseSettings.apiKey = it }
    gorseSettings.feedbackType = feedbackType
    gorseSettings.positiveFeedbackType = positiveFeedbackType
    gorseSettings.negativeFeedbackType = negativeFeedbackType
    newSettings.anonymousUserId?.let { gorseSettings.anonymousUserId = it }
    newSettings.readThreshold?.let { gorseSettings.readThreshold = it }
    newSettings.tagPenaltyExponent?.let { gorseSettings.tagPenaltyExponent = it }
  }

  @PostMapping("sync/items")
  @Operation(summary = "Sync all series to Gorse as items")
  fun syncItems(): GorseSyncResultDto {
    val count = gorseEventListener.syncAllItems()
    return GorseSyncResultDto("items", count)
  }

  @PostMapping("sync/users")
  @Operation(summary = "Sync all users to Gorse")
  fun syncUsers(): GorseSyncResultDto {
    val count = gorseEventListener.syncAllUsers()
    return GorseSyncResultDto("users", count)
  }

  @PostMapping("sync/feedback")
  @Operation(summary = "Sync all completed read progress to Gorse as feedback")
  fun syncFeedback(): GorseSyncResultDto {
    val count = gorseEventListener.syncAllFeedback()
    return GorseSyncResultDto("feedback", count)
  }
}
