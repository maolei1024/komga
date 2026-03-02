package org.gotson.komga.interfaces.api.rest.dto

data class GorseSettingsDto(
  val enabled: Boolean,
  val apiUrl: String,
  val apiKey: String,
  val feedbackType: String,
)

data class GorseSettingsUpdateDto(
  val enabled: Boolean? = null,
  val apiUrl: String? = null,
  val apiKey: String? = null,
  val feedbackType: String? = null,
)

data class GorseSyncResultDto(
  val type: String,
  val count: Int,
)
