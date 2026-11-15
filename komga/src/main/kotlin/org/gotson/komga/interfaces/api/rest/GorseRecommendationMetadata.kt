package org.gotson.komga.interfaces.api.rest

import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import org.springframework.stereotype.Component

@Component
class GorseRecommendationMetadata(
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
) {
  fun enrich(series: List<SeriesDto>): List<SeriesDto> {
    if (series.isEmpty()) return series
    val books = bookRepository.findAllBySeriesIds(series.map { it.id }).filter { it.deletedDate == null }
    val pages = mediaRepository.getPagesSizes(books.map { it.id }).toMap()
    val bySeries = books.groupBy { it.seriesId }
    return series.map { item ->
      val seriesBooks = bySeries[item.id].orEmpty()
      val pagesCount =
        if (seriesBooks.isNotEmpty() && seriesBooks.all { (pages[it.id] ?: 0) > 0 }) {
          seriesBooks.sumOf { pages.getValue(it.id).toLong() }
        } else {
          null
        }
      item.copy(tagCount = GorseRecommendationRanker.countPenaltyTags(item), pagesCount = pagesCount)
    }
  }
}
