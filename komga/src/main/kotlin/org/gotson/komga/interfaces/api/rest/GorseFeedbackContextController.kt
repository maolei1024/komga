package org.gotson.komga.interfaces.api.rest

import org.gotson.komga.infrastructure.gorse.GorseClient
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.ContentRestrictionChecker
import org.gotson.komga.interfaces.api.persistence.SeriesDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.GorseFeaturesStatus
import org.gotson.komga.interfaces.api.rest.dto.GorseFeedbackContextDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/v1/gorse/preference/series", produces = [MediaType.APPLICATION_JSON_VALUE])
class GorseFeedbackContextController(
  private val seriesRepository: SeriesDtoRepository,
  private val contentRestrictionChecker: ContentRestrictionChecker,
  private val metadata: GorseRecommendationMetadata,
  private val client: GorseClient,
  private val settings: GorseSettingsProvider,
) {
  @GetMapping("{seriesId}/context")
  fun getContext(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable seriesId: String,
  ): GorseFeedbackContextDto {
    val series =
      seriesRepository.findByIdOrNull(seriesId, principal.user.id)?.takeUnless { it.deleted }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    contentRestrictionChecker.checkContentRestriction(principal.user, series)
    val enriched = metadata.enrich(listOf(series)).single()
    val result = GorseFeedbackContextDto(seriesId, enriched.pagesCount, enriched.tagCount, GorseFeaturesStatus.UNAVAILABLE)
    if (!settings.enabled) return result
    val item =
      try {
        client.getItemChecked(seriesId)
      } catch (exception: WebClientResponseException) {
        return result.copy(featuresStatus = if (exception.statusCode == HttpStatus.NOT_FOUND) GorseFeaturesStatus.MISSING else GorseFeaturesStatus.UNAVAILABLE)
      } catch (exception: RuntimeException) {
        return result
      }

    fun values(key: String): List<String> =
      (item.Labels[key] as? Collection<*>)
        .orEmpty()
        .filterIsInstance<String>()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
    val buckets = values("preference_buckets")
    return result.copy(
      featuresStatus = GorseFeaturesStatus.AVAILABLE,
      authors = values("authors"),
      tags = values("tags"),
      pageBuckets = buckets.filter { it.startsWith("pagesize_", ignoreCase = true) },
      tagBuckets = buckets.filter { it.startsWith("tagsize_", ignoreCase = true) },
      genres = values("genres"),
      groups = values("groups"),
      characters = values("characters"),
    )
  }
}
