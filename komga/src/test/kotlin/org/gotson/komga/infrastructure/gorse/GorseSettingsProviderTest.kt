package org.gotson.komga.infrastructure.gorse

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.gotson.komga.infrastructure.jooq.main.ServerSettingsDao
import org.junit.jupiter.api.Test

class GorseSettingsProviderTest {
  private val serverSettingsDao = mockk<ServerSettingsDao>()

  @Test
  fun `given no stored tag penalty exponent when creating settings then default is square root`() {
    stubSettings()

    assertThat(GorseSettingsProvider(serverSettingsDao).tagPenaltyExponent).isEqualTo(0.5)
  }

  @Test
  fun `given stored tag penalty exponent when creating settings then value is restored`() {
    stubSettings(tagPenaltyExponent = "0.35")

    assertThat(GorseSettingsProvider(serverSettingsDao).tagPenaltyExponent).isEqualTo(0.35)
  }

  @Test
  fun `when updating tag penalty exponent then value is persisted`() {
    stubSettings()
    every { serverSettingsDao.saveSetting(any(), any<String>()) } just runs
    val settings = GorseSettingsProvider(serverSettingsDao)

    settings.tagPenaltyExponent = 0.75

    assertThat(settings.tagPenaltyExponent).isEqualTo(0.75)
    verify { serverSettingsDao.saveSetting("GORSE_TAG_PENALTY_EXPONENT", "0.75") }
  }

  @Test
  fun `given non finite tag penalty exponent when updating then value is rejected`() {
    stubSettings()
    val settings = GorseSettingsProvider(serverSettingsDao)

    assertThatIllegalArgumentException().isThrownBy {
      settings.tagPenaltyExponent = Double.NaN
    }
  }

  private fun stubSettings(tagPenaltyExponent: String? = null) {
    every { serverSettingsDao.getSettingByKey(any(), Boolean::class.java) } returns null
    every { serverSettingsDao.getSettingByKey(any(), String::class.java) } answers {
      if (firstArg<String>() == "GORSE_TAG_PENALTY_EXPONENT") tagPenaltyExponent else null
    }
  }
}
