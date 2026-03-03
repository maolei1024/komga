package org.gotson.komga.interfaces.api.rest

import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import org.gotson.komga.infrastructure.gorse.GorseClient
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

@RestController
@RequestMapping("api/v1/series", produces = [MediaType.APPLICATION_JSON_VALUE])
class GorseRecommendationController(
  private val gorseClient: GorseClient,
  private val gorseSettings: GorseSettingsProvider,
  private val seriesDtoRepository: SeriesDtoRepository,
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
    val recommendedIds = gorseClient.getRecommendations(userId, n = size * 2, offset = offset)
    logger.debug { "Gorse recommended ${recommendedIds.size} items for user $userId" }

    if (recommendedIds.isEmpty()) {
      return PageImpl(emptyList(), PageRequest.of(page, size), 0)
    }

    val seriesList = recommendedIds.mapNotNull { seriesId ->
      try {
        seriesDtoRepository.findByIdOrNull(seriesId, userId)?.restrictUrl(!principal.user.isAdmin)
      } catch (e: Exception) {
        logger.debug { "Series $seriesId from Gorse not found in Komga" }
        null
      }
    }.take(size)

    val total = if (seriesList.size == size) (offset + size + 1).toLong() else (offset + seriesList.size).toLong()
    return PageImpl(seriesList, PageRequest.of(page, size), total)
  }
}
