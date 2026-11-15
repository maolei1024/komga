package org.gotson.komga.infrastructure.gorse

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.service.SeriesLifecycle
import org.gotson.komga.interfaces.api.ContentRestrictionChecker
import org.gotson.komga.interfaces.api.persistence.SeriesDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.GorsePreference
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDateTime

class GorseReadingPreferenceServiceTest {
  private val preferences = mockk<GorsePreferenceService>()
  private val series = mockk<SeriesDtoRepository>()
  private val books = mockk<BookRepository>()
  private val lifecycle = mockk<SeriesLifecycle>(relaxed = true)
  private val restrictions = mockk<ContentRestrictionChecker>(relaxed = true)
  private val service = GorseReadingPreferenceService(preferences, series, books, lifecycle, restrictions)
  private val user = KomgaUser("test@example.org", "password", id = "user")
  private val seriesDto = mockk<SeriesDto>(relaxed = true)

  @BeforeEach
  fun setup() {
    clearMocks(preferences, series, books, lifecycle, restrictions, seriesDto)
    every { series.findByIdOrNull("series", "user") } returns seriesDto
    every { preferences.setPreferenceAndComplete("user", "series", any(), any()) } answers {
      arg<() -> Unit>(3).invoke()
      arg<GorsePreference>(2)
    }
  }

  @Test
  fun `both preferences complete the snapshot of active books only`() {
    val first = makeBook("first", seriesId = "series")
    val second = makeBook("second", seriesId = "series")
    val deleted = makeBook("deleted", seriesId = "series").copy(deletedDate = LocalDateTime.now())
    every { books.findAllBySeriesId("series") } returns listOf(first, second, deleted)

    service.setPreference(user, "series", GorsePreference.LIKE)
    service.setPreference(user, "series", GorsePreference.DISLIKE)

    verify(exactly = 2) { lifecycle.markReadProgressCompleted("series", user, listOf(first.id, second.id)) }
  }

  @Test
  fun `cancel does not change reading progress or inspect books`() {
    every { preferences.setPreference("user", "series", GorsePreference.NONE) } returns GorsePreference.NONE
    service.setPreference(user, "series", GorsePreference.NONE)
    verify(exactly = 0) { lifecycle.markReadProgressCompleted(any(), any(), any()) }
    verify(exactly = 0) { books.findAllBySeriesId(any()) }
  }

  @Test
  fun `restricted and deleted content is rejected before any feedback mutation`() {
    every { restrictions.checkContentRestriction(user, seriesDto) } throws ResponseStatusException(HttpStatus.FORBIDDEN)
    assertThatThrownBy { service.setPreference(user, "series", GorsePreference.LIKE) }
      .isInstanceOf(ResponseStatusException::class.java)
      .hasMessageContaining("403 FORBIDDEN")

    every { seriesDto.deleted } returns true
    assertThatThrownBy { service.setPreference(user, "series", GorsePreference.DISLIKE) }
      .isInstanceOf(ResponseStatusException::class.java)
      .hasMessageContaining("404 NOT_FOUND")
    verify(exactly = 0) { preferences.setPreferenceAndComplete(any(), any(), any(), any()) }
  }
}
