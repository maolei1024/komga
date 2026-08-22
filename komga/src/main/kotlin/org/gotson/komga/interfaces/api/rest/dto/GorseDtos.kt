package org.gotson.komga.interfaces.api.rest.dto

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank

data class GorseSettingsDto(
  val enabled: Boolean,
  val apiUrl: String,
  val apiKey: String,
  val feedbackType: String,
  val positiveFeedbackType: String,
  val negativeFeedbackType: String,
  val anonymousUserId: String,
  val readThreshold: Double,
  val tagPenaltyExponent: Double,
)

data class GorseSettingsUpdateDto(
  val enabled: Boolean? = null,
  val apiUrl: String? = null,
  val apiKey: String? = null,
  val feedbackType: String? = null,
  val positiveFeedbackType: String? = null,
  val negativeFeedbackType: String? = null,
  val anonymousUserId: String? = null,
  val readThreshold: Double? = null,
  @field:DecimalMin("0.0")
  @field:DecimalMax("1.0")
  val tagPenaltyExponent: Double? = null,
)

data class GorseSyncResultDto(
  val type: String,
  val count: Int,
)

data class GorseConnectionTestRequestDto(
  @field:NotBlank
  val apiUrl: String,
  val apiKey: String = "",
)

data class GorseConnectionTestResultDto(
  val ready: Boolean,
  val dataStoreConnected: Boolean,
  val cacheStoreConnected: Boolean,
  val apiAuthenticated: Boolean,
)

data class GorseConnectionTestErrorDto(
  val message: String,
)

enum class GorsePreference {
  NONE,
  LIKE,
  DISLIKE,
}

data class GorsePreferenceUpdateDto(
  val preference: GorsePreference,
)

data class GorsePreferenceDto(
  val seriesId: String,
  val preference: GorsePreference,
)
