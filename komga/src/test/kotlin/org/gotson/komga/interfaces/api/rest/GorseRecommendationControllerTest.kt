package org.gotson.komga.interfaces.api.rest

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.infrastructure.gorse.GorseClient
import org.gotson.komga.infrastructure.gorse.GorseRecommendation
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.persistence.SeriesDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.BookMetadataAggregationDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesMetadataDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GorseRecommendationControllerTest {
  private val gorseClient = mockk<GorseClient>()
  private val gorseSettings = mockk<GorseSettingsProvider>()
  private val seriesDtoRepository = mockk<SeriesDtoRepository>()
  private val principal = mockk<KomgaPrincipal>()
  private val user = mockk<KomgaUser>()
  private val controller = GorseRecommendationController(gorseClient, gorseSettings, seriesDtoRepository)

  @BeforeEach
  fun setup() {
    clearMocks(gorseClient, gorseSettings, seriesDtoRepository, principal, user)
    every { gorseSettings.enabled } returns true
    every { gorseSettings.tagPenaltyExponent } returns 0.0
    every { principal.user } returns user
    every { user.id } returns "user"
    every { user.isAdmin } returns true
  }

  @Test
  fun `given disabled Gorse when retrieving recommendations then empty page is returned`() {
    every { gorseSettings.enabled } returns false

    val result = controller.getRecommendedSeries(principal, page = 0, size = 20)

    assertThat(result.content).isEmpty()
    assertThat(result.totalElements).isZero()
    verify(exactly = 0) { gorseClient.getRecommendations(any(), any(), any()) }
  }

  @Test
  fun `given missing candidates when retrieving multiple pages then filtering happens before stable pagination`() {
    val recommendations =
      (1..6).map { index ->
        GorseRecommendation(Id = "series-$index", Score = 1.0 - index / 10.0)
      }
    val visibleSeries =
      mapOf(
        "series-1" to series(),
        "series-3" to series(),
        "series-4" to series(),
        "series-5" to series(),
        "series-6" to series(),
      )
    every { gorseClient.getRecommendations("user", n = 100, offset = 0) } returns recommendations
    every { seriesDtoRepository.findByIdOrNull(any(), "user") } answers {
      visibleSeries[firstArg<String>()]
    }

    val firstPage = controller.getRecommendedSeries(principal, page = 0, size = 2)
    val secondPage = controller.getRecommendedSeries(principal, page = 1, size = 2)

    assertThat(firstPage.content).containsExactly(visibleSeries["series-1"], visibleSeries["series-3"])
    assertThat(secondPage.content).containsExactly(visibleSeries["series-4"], visibleSeries["series-5"])
    assertThat(firstPage.content).doesNotContainAnyElementsOf(secondPage.content)
    assertThat(firstPage.totalElements).isEqualTo(5)
    assertThat(secondPage.totalElements).isEqualTo(5)
    verify(exactly = 2) { gorseClient.getRecommendations("user", n = 100, offset = 0) }
  }

  @Test
  fun `given tag penalty disabled when ranking then original Gorse order is preserved`() {
    val first = series()
    val second = series()
    every { gorseClient.getRecommendations("user", n = 100, offset = 0) } returns
      listOf(
        GorseRecommendation(Id = "first", Score = 0.1),
        GorseRecommendation(Id = "second", Score = 0.9),
      )
    every { seriesDtoRepository.findByIdOrNull("first", "user") } returns first
    every { seriesDtoRepository.findByIdOrNull("second", "user") } returns second

    val result = controller.getRecommendedSeries(principal, page = 0, size = 20)

    assertThat(result.content).containsExactly(first, second)
  }

  private fun series(
    metadataTags: Set<String> = emptySet(),
    bookMetadataTags: Set<String> = emptySet(),
  ): SeriesDto {
    val metadata = mockk<SeriesMetadataDto>()
    val booksMetadata = mockk<BookMetadataAggregationDto>()
    every { metadata.tags } returns metadataTags
    every { booksMetadata.tags } returns bookMetadataTags

    val series = mockk<SeriesDto>()
    every { series.metadata } returns metadata
    every { series.booksMetadata } returns booksMetadata
    return series
  }
}
