package org.gotson.komga.infrastructure.gorse

import org.gotson.komga.infrastructure.jooq.main.ServerSettingsDao
import org.springframework.stereotype.Service

@Service
class GorseSettingsProvider(
  private val serverSettingsDao: ServerSettingsDao,
) {
  companion object {
    internal const val DEFAULT_TAG_PENALTY_EXPONENT = 0.5
    internal const val MIN_TAG_PENALTY_EXPONENT = 0.0
    internal const val MAX_TAG_PENALTY_EXPONENT = 1.0
  }

  var enabled: Boolean =
    serverSettingsDao.getSettingByKey(GorseSettings.GORSE_ENABLED.name, Boolean::class.java) ?: false
    set(value) {
      serverSettingsDao.saveSetting(GorseSettings.GORSE_ENABLED.name, value)
      field = value
    }

  var apiUrl: String =
    serverSettingsDao.getSettingByKey(GorseSettings.GORSE_API_URL.name, String::class.java) ?: "http://localhost:8087"
    set(value) {
      serverSettingsDao.saveSetting(GorseSettings.GORSE_API_URL.name, value)
      field = value
    }

  var apiKey: String =
    serverSettingsDao.getSettingByKey(GorseSettings.GORSE_API_KEY.name, String::class.java) ?: ""
    set(value) {
      serverSettingsDao.saveSetting(GorseSettings.GORSE_API_KEY.name, value)
      field = value
    }

  var feedbackType: String =
    serverSettingsDao.getSettingByKey(GorseSettings.GORSE_FEEDBACK_TYPE.name, String::class.java) ?: "read"
    set(value) {
      serverSettingsDao.saveSetting(GorseSettings.GORSE_FEEDBACK_TYPE.name, value)
      field = value
    }

  var positiveFeedbackType: String =
    serverSettingsDao.getSettingByKey(GorseSettings.GORSE_POSITIVE_FEEDBACK_TYPE.name, String::class.java) ?: "like"
    set(value) {
      serverSettingsDao.saveSetting(GorseSettings.GORSE_POSITIVE_FEEDBACK_TYPE.name, value)
      field = value
    }

  var negativeFeedbackType: String =
    serverSettingsDao.getSettingByKey(GorseSettings.GORSE_NEGATIVE_FEEDBACK_TYPE.name, String::class.java) ?: "dislike"
    set(value) {
      serverSettingsDao.saveSetting(GorseSettings.GORSE_NEGATIVE_FEEDBACK_TYPE.name, value)
      field = value
    }

  var anonymousUserId: String =
    serverSettingsDao.getSettingByKey(GorseSettings.GORSE_ANONYMOUS_USER_ID.name, String::class.java) ?: ""
    set(value) {
      serverSettingsDao.saveSetting(GorseSettings.GORSE_ANONYMOUS_USER_ID.name, value)
      field = value
    }

  var readThreshold: Double =
    serverSettingsDao.getSettingByKey(GorseSettings.GORSE_READ_THRESHOLD.name, String::class.java)?.toDoubleOrNull() ?: 0.5
    set(value) {
      serverSettingsDao.saveSetting(GorseSettings.GORSE_READ_THRESHOLD.name, value.toString())
      field = value
    }

  var tagPenaltyExponent: Double =
    serverSettingsDao
      .getSettingByKey(GorseSettings.GORSE_TAG_PENALTY_EXPONENT.name, String::class.java)
      ?.toDoubleOrNull()
      ?.takeIf { it.isValidTagPenaltyExponent() }
      ?: DEFAULT_TAG_PENALTY_EXPONENT
    set(value) {
      require(value.isValidTagPenaltyExponent()) {
        "Tag penalty exponent must be finite and between $MIN_TAG_PENALTY_EXPONENT and $MAX_TAG_PENALTY_EXPONENT"
      }
      serverSettingsDao.saveSetting(GorseSettings.GORSE_TAG_PENALTY_EXPONENT.name, value.toString())
      field = value
    }
}

private fun Double.isValidTagPenaltyExponent(): Boolean =
  isFinite() &&
    this in GorseSettingsProvider.MIN_TAG_PENALTY_EXPONENT..GorseSettingsProvider.MAX_TAG_PENALTY_EXPONENT

private enum class GorseSettings {
  GORSE_ENABLED,
  GORSE_API_URL,
  GORSE_API_KEY,
  GORSE_FEEDBACK_TYPE,
  GORSE_POSITIVE_FEEDBACK_TYPE,
  GORSE_NEGATIVE_FEEDBACK_TYPE,
  GORSE_ANONYMOUS_USER_ID,
  GORSE_READ_THRESHOLD,
  GORSE_TAG_PENALTY_EXPONENT,
}
