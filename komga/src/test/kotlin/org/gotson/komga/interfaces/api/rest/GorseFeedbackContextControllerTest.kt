package org.gotson.komga.interfaces.api.rest

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.infrastructure.gorse.GorseClient
import org.gotson.komga.infrastructure.gorse.GorseItem
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentDictionaryService
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.ContentRestrictionChecker
import org.gotson.komga.interfaces.api.persistence.SeriesDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.GorseFeaturesStatus
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException

class GorseFeedbackContextControllerTest {
  private val seriesRepository = mockk<SeriesDtoRepository>()
  private val checker = mockk<ContentRestrictionChecker>(relaxed = true)
  private val metadata = mockk<GorseRecommendationMetadata>()
  private val client = mockk<GorseClient>()
  private val settings = mockk<GorseSettingsProvider>()
  private val dictionary = mockk<MetadataEnrichmentDictionaryService>()
  private val principal = mockk<KomgaPrincipal>()
  private val user = mockk<KomgaUser>()
  private val series = mockk<SeriesDto>()
  private val controller = GorseFeedbackContextController(seriesRepository, checker, metadata, client, settings, dictionary)

  @BeforeEach
  fun setup() {
    clearMocks(seriesRepository, checker, metadata, client, settings, dictionary, principal, user, series)
    every { dictionary.lookup("tag", any()) } returns null
    every { principal.user } returns user
    every { user.id } returns "user"
    every { settings.enabled } returns true
    every { seriesRepository.findByIdOrNull("series", "user") } returns series
    every { series.deleted } returns false
    every { series.pagesCount } returns 36L
    every { series.tagCount } returns 28
    every { metadata.enrich(listOf(series)) } returns listOf(series)
  }

  @Test
  fun `returns actual Gorse features independently of card counts or current bucket configuration`() {
    every { dictionary.lookup("tag", "romance") } returns "恋爱"
    every { client.getItemChecked("series") } returns
      GorseItem(
        "series",
        Labels =
          mapOf(
            "tags" to listOf("romance", "action"),
            "authors" to listOf("artist one", "artist two"),
            "preference_buckets" to listOf("pagesize_historical", "tagsize_0-5"),
            "genres" to listOf("fantasy"),
            "groups" to listOf("circle"),
            "characters" to listOf("hero"),
          ),
        Timestamp = "2026-11-15T00:00:00Z",
      )

    val result = controller.getContext(principal, "series")

    assertThat(result.featuresStatus).isEqualTo(GorseFeaturesStatus.AVAILABLE)
    assertThat(result.pagesCount).isEqualTo(36L)
    assertThat(result.tagCount).isEqualTo(28)
    assertThat(result.tags).containsExactly("romance", "action")
    assertThat(result.displayTags).containsExactly("恋爱", "action")
    assertThat(result.authors).containsExactly("artist one", "artist two")
    assertThat(result.pageBuckets).containsExactly("pagesize_historical")
    assertThat(result.tagBuckets).containsExactly("tagsize_0-5")
    assertThat(result.genres).containsExactly("fantasy")
    assertThat(result.groups).containsExactly("circle")
    assertThat(result.characters).containsExactly("hero")
    verify { checker.checkContentRestriction(user, series) }
  }

  @Test
  fun `missing and failed Gorse requests keep local metadata and do not infer features`() {
    for ((status, expected) in listOf(404 to GorseFeaturesStatus.MISSING, 503 to GorseFeaturesStatus.UNAVAILABLE)) {
      every { client.getItemChecked("series") } throws WebClientResponseException.create(status, "failure", HttpHeaders.EMPTY, byteArrayOf(), null)
      val result = controller.getContext(principal, "series")
      assertThat(result.featuresStatus).isEqualTo(expected)
      assertThat(result.pagesCount).isEqualTo(36L)
      assertThat(result.tags).isEmpty()
      assertThat(result.displayTags).isEmpty()
      assertThat(result.pageBuckets).isEmpty()
    }
    every { client.getItemChecked("series") } throws IllegalStateException("network unavailable")
    assertThat(controller.getContext(principal, "series").featuresStatus).isEqualTo(GorseFeaturesStatus.UNAVAILABLE)
  }

  @Test
  fun `unknown pages and malformed feature groups stay unknown`() {
    every { series.pagesCount } returns null
    every { client.getItemChecked("series") } returns
      GorseItem("series", Labels = mapOf("authors" to "invalid", "tags" to listOf("", 5, "romance", "romance")), Timestamp = "2026-11-15T00:00:00Z")
    val result = controller.getContext(principal, "series")
    assertThat(result.pagesCount).isNull()
    assertThat(result.authors).isEmpty()
    assertThat(result.tags).containsExactly("romance")
    assertThat(result.displayTags).containsExactly("romance")
    verify(exactly = 1) { dictionary.lookup("tag", "romance") }
    assertThat(result.tagBuckets).isEmpty()
  }

  @Test
  fun `disabled Gorse does not call its API`() {
    every { settings.enabled } returns false
    assertThat(controller.getContext(principal, "series").featuresStatus).isEqualTo(GorseFeaturesStatus.UNAVAILABLE)
    verify(exactly = 0) { client.getItemChecked(any()) }
  }

  @Test
  fun `missing deleted and forbidden series are rejected before metadata or Gorse are fetched`() {
    every { seriesRepository.findByIdOrNull("missing", "user") } returns null
    assertThatThrownBy { controller.getContext(principal, "missing") }.isInstanceOf(ResponseStatusException::class.java)
    every { series.deleted } returns true
    assertThatThrownBy { controller.getContext(principal, "series") }.hasMessageContaining("404")
    every { series.deleted } returns false
    every { checker.checkContentRestriction(user, series) } throws ResponseStatusException(HttpStatus.FORBIDDEN)
    assertThatThrownBy { controller.getContext(principal, "series") }.hasMessageContaining("403")
    verify(exactly = 0) { metadata.enrich(any()) }
    verify(exactly = 0) { client.getItemChecked(any()) }
  }
}
