package org.gotson.komga.interfaces.api.rest

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.gotson.komga.infrastructure.gorse.GorsePreferenceService
import org.gotson.komga.infrastructure.gorse.GorseReadingPreferenceService
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.ContentRestrictionChecker
import org.gotson.komga.interfaces.api.persistence.BookDtoRepository
import org.gotson.komga.interfaces.api.persistence.SeriesDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.GorsePreferenceDto
import org.gotson.komga.interfaces.api.rest.dto.GorsePreferenceUpdateDto
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
@RequestMapping("api/v1/gorse/preference", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "gorse-preference")
class GorsePreferenceController(
  private val preferenceService: GorsePreferenceService,
  private val gorseSettings: GorseSettingsProvider,
  private val seriesDtoRepository: SeriesDtoRepository,
  private val bookDtoRepository: BookDtoRepository,
  private val readingPreferenceService: GorseReadingPreferenceService,
  private val contentRestrictionChecker: ContentRestrictionChecker,
) {
  @GetMapping("series/{seriesId}")
  @Operation(summary = "Get the current user's explicit preference for a series")
  fun getSeriesPreference(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable seriesId: String,
  ): GorsePreferenceDto {
    requireEnabled()
    requireSeries(seriesId, principal)
    return GorsePreferenceDto(seriesId, preferenceService.getPreference(principal.user.id, seriesId))
  }

  @PutMapping("series/{seriesId}")
  @Operation(summary = "Set the current user's explicit preference for a series")
  fun setSeriesPreference(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable seriesId: String,
    @RequestBody update: GorsePreferenceUpdateDto,
  ): GorsePreferenceDto {
    requireEnabled()
    requireSeries(seriesId, principal)
    return GorsePreferenceDto(seriesId, readingPreferenceService.setPreference(principal.user, seriesId, update.preference))
  }

  @GetMapping("book/{bookId}")
  @Operation(summary = "Get the current user's explicit preference for the series containing a book")
  fun getBookPreference(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable bookId: String,
  ): GorsePreferenceDto {
    requireEnabled()
    val seriesId = requireBookSeries(bookId, principal)
    return GorsePreferenceDto(seriesId, preferenceService.getPreference(principal.user.id, seriesId))
  }

  @PutMapping("book/{bookId}")
  @Operation(summary = "Set the current user's explicit preference for the series containing a book")
  fun setBookPreference(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable bookId: String,
    @RequestBody update: GorsePreferenceUpdateDto,
  ): GorsePreferenceDto {
    requireEnabled()
    val seriesId = requireBookSeries(bookId, principal)
    return GorsePreferenceDto(seriesId, readingPreferenceService.setPreference(principal.user, seriesId, update.preference))
  }

  private fun requireSeries(
    seriesId: String,
    principal: KomgaPrincipal,
  ) {
    val series =
      seriesDtoRepository.findByIdOrNull(seriesId, principal.user.id)?.takeUnless { it.deleted }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    contentRestrictionChecker.checkContentRestriction(principal.user, series)
  }

  private fun requireBookSeries(
    bookId: String,
    principal: KomgaPrincipal,
  ): String {
    val book =
      bookDtoRepository.findByIdOrNull(bookId, principal.user.id)?.takeUnless { it.deleted }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    contentRestrictionChecker.checkContentRestriction(principal.user, book)
    return book.seriesId
  }

  private fun requireEnabled() {
    if (!gorseSettings.enabled) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Gorse is disabled")
  }
}
