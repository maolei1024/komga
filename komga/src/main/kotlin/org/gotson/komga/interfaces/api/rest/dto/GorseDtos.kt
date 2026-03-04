package org.gotson.komga.interfaces.api.rest.dto

data class GorseSettingsDto(
  val enabled: Boolean,
  val apiUrl: String,
  val apiKey: String,
  val feedbackType: String,
  val positiveFeedbackType: String,
  val anonymousUserId: String,
)

data class GorseSettingsUpdateDto(
  val enabled: Boolean? = null,
  val apiUrl: String? = null,
  val apiKey: String? = null,
  val feedbackType: String? = null,
  val positiveFeedbackType: String? = null,
  val anonymousUserId: String? = null,
)

data class GorseSyncResultDto(
  val type: String,
  val count: Int,
)
