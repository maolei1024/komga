package org.gotson.komga.interfaces.api.rest.dto

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin

data class GorseSettingsDto(
  val enabled: Boolean,
  val apiUrl: String,
  val apiKey: String,
  val feedbackType: String,
  val positiveFeedbackType: String,
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
