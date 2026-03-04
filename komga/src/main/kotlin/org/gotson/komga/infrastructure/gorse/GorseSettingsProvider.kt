package org.gotson.komga.infrastructure.gorse

import org.gotson.komga.infrastructure.jooq.main.ServerSettingsDao
import org.springframework.stereotype.Service

@Service
class GorseSettingsProvider(
  private val serverSettingsDao: ServerSettingsDao,
) {
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

  var anonymousUserId: String =
    serverSettingsDao.getSettingByKey(GorseSettings.GORSE_ANONYMOUS_USER_ID.name, String::class.java) ?: ""
    set(value) {
      serverSettingsDao.saveSetting(GorseSettings.GORSE_ANONYMOUS_USER_ID.name, value)
      field = value
    }
}

private enum class GorseSettings {
  GORSE_ENABLED,
  GORSE_API_URL,
  GORSE_API_KEY,
  GORSE_FEEDBACK_TYPE,
  GORSE_POSITIVE_FEEDBACK_TYPE,
  GORSE_ANONYMOUS_USER_ID,
}
