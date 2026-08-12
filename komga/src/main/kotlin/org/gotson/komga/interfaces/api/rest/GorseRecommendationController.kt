package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.infrastructure.gorse.GorseClient
import org.gotson.komga.infrastructure.gorse.GorsePreferenceService
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.persistence.SeriesDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import org.gotson.komga.interfaces.api.rest.dto.restrictUrl
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

private val logger = KotlinLogging.logger {}
private const val RECOMMENDATION_CANDIDATE_COUNT = 100

@RestController
@RequestMapping("api/v1/series", produces = [MediaType.APPLICATION_JSON_VALUE])
class GorseRecommendationController(
  private val gorseClient: GorseClient,
  private val gorseSettings: GorseSettingsProvider,
  private val seriesDtoRepository: SeriesDtoRepository,
  private val preferenceService: GorsePreferenceService,
) {
  @GetMapping("recommended")
  @Operation(summary = "Get recommended series from Gorse", description = "Returns series recommended by Gorse for the current user.")
  fun getRecommendedSeries(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @RequestParam(name = "page", defaultValue = "0") page: Int,
    @RequestParam(name = "size", defaultValue = "20") size: Int,
  ): Page<SeriesDto> {
    if (!gorseSettings.enabled) {
      return PageImpl(emptyList(), PageRequest.of(page, size), 0)
    }

    val userId = principal.user.id
    val offset = page * size
    val recommendations = gorseClient.getRecommendations(userId, n = RECOMMENDATION_CANDIDATE_COUNT, offset = 0)
    logger.debug { "Gorse recommended ${recommendations.size} candidate items for user $userId" }

    if (recommendations.isEmpty()) {
      return PageImpl(emptyList(), PageRequest.of(page, size), 0)
    }

    val dislikedSeriesIds =
      try {
        preferenceService.getDislikedSeriesIds(userId)
      } catch (e: Exception) {
        logger.error(e) { "Failed to retrieve Gorse dislikes for user $userId; returning unfiltered recommendations" }
        emptySet()
      }

    val candidates =
      recommendations
        .filterNot { it.Id in dislikedSeriesIds }
        .mapNotNull { recommendation ->
          try {
            seriesDtoRepository
              .findByIdOrNull(recommendation.Id, userId)
              ?.restrictUrl(!principal.user.isAdmin)
              ?.let { recommendation to it }
          } catch (e: Exception) {
            logger.debug { "Series ${recommendation.Id} from Gorse not found in Komga" }
            null
          }
        }

    val rankedSeries =
      GorseRecommendationRanker.rank(
        candidates = candidates,
        tagPenaltyExponent = gorseSettings.tagPenaltyExponent,
      )
    val seriesList = rankedSeries.drop(offset).take(size)

    logger.debug {
      "Gorse reranked ${rankedSeries.size} visible candidates for user $userId " +
        "with tag penalty exponent ${gorseSettings.tagPenaltyExponent}"
    }
    return PageImpl(seriesList, PageRequest.of(page, size), rankedSeries.size.toLong())
  }
}
