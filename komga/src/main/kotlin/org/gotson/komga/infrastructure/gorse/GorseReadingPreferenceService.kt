package org.gotson.komga.infrastructure.gorse

import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.service.SeriesLifecycle
import org.gotson.komga.interfaces.api.ContentRestrictionChecker
import org.gotson.komga.interfaces.api.persistence.SeriesDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.GorsePreference
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class GorseReadingPreferenceService(
  private val preferenceService: GorsePreferenceService,
  private val seriesRepository: SeriesDtoRepository,
  private val bookRepository: BookRepository,
  private val seriesLifecycle: SeriesLifecycle,
  private val contentRestrictionChecker: ContentRestrictionChecker,
) {
  fun setPreference(
    user: KomgaUser,
    seriesId: String,
    preference: GorsePreference,
  ): GorsePreference {
    val series =
      seriesRepository.findByIdOrNull(seriesId, user.id)?.takeUnless { it.deleted }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    contentRestrictionChecker.checkContentRestriction(user, series)
    if (preference == GorsePreference.NONE) return preferenceService.setPreference(user.id, seriesId, preference)

    val bookIds = bookRepository.findAllBySeriesId(seriesId).filter { it.deletedDate == null }.map { it.id }
    if (bookIds.isEmpty()) throw ResponseStatusException(HttpStatus.CONFLICT, "Series has no available books")
    return preferenceService.setPreferenceAndComplete(user.id, seriesId, preference) {
      seriesLifecycle.markReadProgressCompleted(seriesId, user, bookIds)
    }
  }
}
