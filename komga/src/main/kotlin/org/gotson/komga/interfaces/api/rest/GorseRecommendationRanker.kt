package org.gotson.komga.interfaces.api.rest

import org.gotson.komga.infrastructure.gorse.GorseRecommendation
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import java.util.Locale
import kotlin.math.pow

internal object GorseRecommendationRanker {
  private val technicalTagPrefixes = listOf("tagsize_", "pagesize_")

  fun rank(
    candidates: List<Pair<GorseRecommendation, SeriesDto>>,
    tagPenaltyExponent: Double,
  ): List<SeriesDto> {
    require(tagPenaltyExponent.isFinite() && tagPenaltyExponent in 0.0..1.0)
    if (tagPenaltyExponent == 0.0) return candidates.map { it.second }

    return candidates
      .mapIndexed { index, (recommendation, series) ->
        RankedCandidate(
          series = series,
          originalIndex = index,
          adjustedScore =
            recommendation.Score /
              countPenaltyTags(series).coerceAtLeast(1).toDouble().pow(tagPenaltyExponent),
        )
      }.sortedWith(
        compareByDescending<RankedCandidate> { it.adjustedScore }
          .thenBy { it.originalIndex },
      ).map { it.series }
  }

  internal fun countPenaltyTags(series: SeriesDto): Int =
    (series.metadata.tags.asSequence() + series.booksMetadata.tags.asSequence())
      .map { it.trim() }
      .filter { it.isNotEmpty() }
      .filterNot { tag -> technicalTagPrefixes.any { tag.startsWith(it, ignoreCase = true) } }
      .map { it.lowercase(Locale.ROOT) }
      .toSet()
      .size

  private data class RankedCandidate(
    val series: SeriesDto,
    val originalIndex: Int,
    val adjustedScore: Double,
  )
}
