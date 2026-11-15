package org.gotson.komga.interfaces.api.rest

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.infrastructure.gorse.GorsePreferenceService
import org.gotson.komga.infrastructure.gorse.GorseReadingPreferenceService
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.ContentRestrictionChecker
import org.gotson.komga.interfaces.api.persistence.BookDtoRepository
import org.gotson.komga.interfaces.api.persistence.SeriesDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.BookDto
import org.gotson.komga.interfaces.api.rest.dto.GorsePreference
import org.gotson.komga.interfaces.api.rest.dto.GorsePreferenceUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException

class GorsePreferenceControllerTest {
  private val preferenceService = mockk<GorsePreferenceService>()
  private val settings = mockk<GorseSettingsProvider>()
  private val seriesRepository = mockk<SeriesDtoRepository>()
  private val bookRepository = mockk<BookDtoRepository>()
  private val readingPreferenceService = mockk<GorseReadingPreferenceService>()
  private val contentRestrictionChecker = mockk<ContentRestrictionChecker>(relaxed = true)
  private val principal = mockk<KomgaPrincipal>()
  private val user = mockk<KomgaUser>()
  private val controller = GorsePreferenceController(preferenceService, settings, seriesRepository, bookRepository, readingPreferenceService, contentRestrictionChecker)

  @BeforeEach
  fun setup() {
    clearMocks(preferenceService, readingPreferenceService, settings, seriesRepository, bookRepository, principal, user, contentRestrictionChecker)
    every { settings.enabled } returns true
    every { principal.user } returns user
    every { user.id } returns "user"
  }

  @Test
  fun `series and book endpoints resolve visible content and return authoritative preference`() {
    every { seriesRepository.findByIdOrNull("series", "user") } returns mockk<SeriesDto>(relaxed = true)
    val book = mockk<BookDto>()
    every { book.seriesId } returns "series"
    every { book.deleted } returns false
    every { bookRepository.findByIdOrNull("book", "user") } returns book
    every { preferenceService.getPreference("user", "series") } returns GorsePreference.DISLIKE
    every { readingPreferenceService.setPreference(user, "series", GorsePreference.LIKE) } returns GorsePreference.LIKE

    assertThat(controller.getSeriesPreference(principal, "series").preference).isEqualTo(GorsePreference.DISLIKE)
    assertThat(controller.getBookPreference(principal, "book").seriesId).isEqualTo("series")
    assertThat(
      controller.setBookPreference(principal, "book", GorsePreferenceUpdateDto(GorsePreference.LIKE)).preference,
    ).isEqualTo(GorsePreference.LIKE)
  }

  @Test
  fun `invisible content is rejected before Gorse is called`() {
    every { seriesRepository.findByIdOrNull("hidden", "user") } returns null

    assertThatThrownBy { controller.getSeriesPreference(principal, "hidden") }
      .isInstanceOf(ResponseStatusException::class.java)
      .hasMessageContaining("404 NOT_FOUND")
    verify(exactly = 0) { preferenceService.getPreference(any(), any()) }
  }

  @Test
  fun `invisible book is rejected before Gorse is called`() {
    every { bookRepository.findByIdOrNull("hidden", "user") } returns null

    assertThatThrownBy { controller.getBookPreference(principal, "hidden") }
      .isInstanceOf(ResponseStatusException::class.java)
      .hasMessageContaining("404 NOT_FOUND")
    verify(exactly = 0) { preferenceService.getPreference(any(), any()) }
  }
}
