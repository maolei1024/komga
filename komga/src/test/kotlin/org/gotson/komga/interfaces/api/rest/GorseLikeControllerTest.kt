package org.gotson.komga.interfaces.api.rest

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.infrastructure.gorse.GorsePreferenceService
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.rest.dto.GorsePreference
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GorseLikeControllerTest {
  private val preferenceService = mockk<GorsePreferenceService>()
  private val settings = mockk<GorseSettingsProvider>()
  private val bookRepository = mockk<BookRepository>()
  private val principal = mockk<KomgaPrincipal>()
  private val user = mockk<KomgaUser>()
  private val controller = GorseLikeController(preferenceService, settings, bookRepository)

  @BeforeEach
  fun setup() {
    every { settings.enabled } returns true
    every { principal.user } returns user
    every { user.id } returns "user"
  }

  @Test
  fun `legacy like and unlike routes delegate to mutually exclusive preference transitions`() {
    every { preferenceService.setPreference("user", "series", GorsePreference.LIKE) } returns GorsePreference.LIKE
    every { preferenceService.setPreference("user", "series", GorsePreference.NONE) } returns GorsePreference.NONE

    assertThat(controller.likeSeries(principal, "series")["success"]).isTrue()
    assertThat(controller.unlikeSeries(principal, "series")["success"]).isTrue()

    verify { preferenceService.setPreference("user", "series", GorsePreference.LIKE) }
    verify { preferenceService.setPreference("user", "series", GorsePreference.NONE) }
  }
}
