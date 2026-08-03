package org.gotson.komga.domain.service

import org.gotson.komga.domain.model.DedupPageFeature
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.infrastructure.dedup.CoverPerceptualHasher
import org.springframework.stereotype.Service

data class PageAlignmentResult(
  val relationType: DedupRelationType,
  val matches: List<PageMatch>,
  val coverageLeft: Double,
  val coverageRight: Double,
  val longestMatchedRun: Int,
  val unmatchedLeft: UnmatchedPages,
  val unmatchedRight: UnmatchedPages,
  val containedBookId: String? = null,
  val containerBookId: String? = null,
)

data class PageMatch(
  val leftPage: Int,
  val rightPage: Int,
  val exact: Boolean,
)

data class UnmatchedPages(
  val prefixCount: Int,
  val suffixCount: Int,
  val internalCount: Int,
  val ranges: List<String>,
) {
  val total: Int = prefixCount + suffixCount + internalCount
}

@Service
class PageSequenceAligner(
  private val perceptualHasher: CoverPerceptualHasher,
) {
  fun align(
    leftBookId: String,
    left: List<DedupPageFeature>,
    rightBookId: String,
    right: List<DedupPageFeature>,
    perceptualDistance: Int = 8,
  ): PageAlignmentResult {
    if (left.isEmpty() || right.isEmpty()) {
      return PageAlignmentResult(
        relationType = DedupRelationType.VISUALLY_SIMILAR,
        matches = emptyList(),
        coverageLeft = 0.0,
        coverageRight = 0.0,
        longestMatchedRun = 0,
        unmatchedLeft = unmatched(left.size, emptySet()),
        unmatchedRight = unmatched(right.size, emptySet()),
      )
    }

    val matchKinds = Array(left.size) { arrayOfNulls<MatchKind>(right.size) }
    val scores = Array(left.size + 1) { IntArray(right.size + 1) }
    for (leftIndex in left.indices.reversed()) {
      for (rightIndex in right.indices.reversed()) {
        val kind = matchKind(left[leftIndex], right[rightIndex], perceptualDistance)
        matchKinds[leftIndex][rightIndex] = kind
        val diagonal = kind?.let { scores[leftIndex + 1][rightIndex + 1] + it.score } ?: Int.MIN_VALUE
        scores[leftIndex][rightIndex] = maxOf(diagonal, scores[leftIndex + 1][rightIndex], scores[leftIndex][rightIndex + 1])
      }
    }

    val matches = mutableListOf<PageMatch>()
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.size && rightIndex < right.size) {
      val kind = matchKinds[leftIndex][rightIndex]
      if (kind != null && scores[leftIndex][rightIndex] == scores[leftIndex + 1][rightIndex + 1] + kind.score) {
        matches += PageMatch(leftIndex + 1, rightIndex + 1, kind == MatchKind.EXACT)
        leftIndex++
        rightIndex++
      } else if (scores[leftIndex + 1][rightIndex] >= scores[leftIndex][rightIndex + 1]) {
        leftIndex++
      } else {
        rightIndex++
      }
    }

    val matchedLeft = matches.map { it.leftPage }.toSet()
    val matchedRight = matches.map { it.rightPage }.toSet()
    val coverageLeft = matches.size.toDouble() / left.size
    val coverageRight = matches.size.toDouble() / right.size
    val unmatchedLeft = unmatched(left.size, matchedLeft)
    val unmatchedRight = unmatched(right.size, matchedRight)
    val allExact = matches.all { it.exact }
    val classification = classify(leftBookId, rightBookId, left.size, right.size, coverageLeft, coverageRight, unmatchedLeft, unmatchedRight, allExact)

    return PageAlignmentResult(
      relationType = classification.type,
      matches = matches,
      coverageLeft = coverageLeft,
      coverageRight = coverageRight,
      longestMatchedRun = longestRun(matches),
      unmatchedLeft = unmatchedLeft,
      unmatchedRight = unmatchedRight,
      containedBookId = classification.containedBookId,
      containerBookId = classification.containerBookId,
    )
  }

  private fun matchKind(
    left: DedupPageFeature,
    right: DedupPageFeature,
    perceptualDistance: Int,
  ): MatchKind? =
    when {
      !left.exactHash.isNullOrBlank() && left.exactHash == right.exactHash -> MatchKind.EXACT
      left.perceptualHash?.size == 32 && right.perceptualHash?.size == 32 &&
        perceptualHasher.distance(left.perceptualHash, right.perceptualHash) <= perceptualDistance -> MatchKind.PERCEPTUAL
      else -> null
    }

  private fun classify(
    leftBookId: String,
    rightBookId: String,
    leftSize: Int,
    rightSize: Int,
    coverageLeft: Double,
    coverageRight: Double,
    unmatchedLeft: UnmatchedPages,
    unmatchedRight: UnmatchedPages,
    allExact: Boolean,
  ): Classification {
    if (leftSize == rightSize && coverageLeft == 1.0 && allExact) return Classification(DedupRelationType.EXACT_PAGE_SEQUENCE)
    if (coverageLeft == 1.0 && allExact && leftSize <= rightSize) {
      return Classification(DedupRelationType.CONTAINED_IN, leftBookId, rightBookId)
    }
    if (coverageRight == 1.0 && allExact && rightSize <= leftSize) {
      return Classification(DedupRelationType.CONTAINED_IN, rightBookId, leftBookId)
    }
    if (coverageLeft >= 0.9 && unmatchedLeft.total > 0 && coverageLeft >= coverageRight) {
      return Classification(DedupRelationType.NEAR_CONTAINED_IN, leftBookId, rightBookId)
    }
    if (coverageRight >= 0.9 && unmatchedRight.total > 0 && coverageRight >= coverageLeft) {
      return Classification(DedupRelationType.NEAR_CONTAINED_IN, rightBookId, leftBookId)
    }
    if (coverageLeft >= 0.8 && coverageRight >= 0.8) return Classification(DedupRelationType.EDITION_UNCERTAIN)
    if (coverageLeft >= 0.3 || coverageRight >= 0.3) return Classification(DedupRelationType.PARTIAL_OVERLAP)
    return Classification(DedupRelationType.VISUALLY_SIMILAR)
  }

  private fun longestRun(matches: List<PageMatch>): Int {
    var longest = 0
    var current = 0
    var previous: PageMatch? = null
    matches.forEach { match ->
      current = if (previous != null && match.leftPage == previous!!.leftPage + 1 && match.rightPage == previous!!.rightPage + 1) current + 1 else 1
      longest = maxOf(longest, current)
      previous = match
    }
    return longest
  }

  private fun unmatched(
    pageCount: Int,
    matched: Set<Int>,
  ): UnmatchedPages {
    val missing = (1..pageCount).filterNot(matched::contains)
    if (missing.isEmpty()) return UnmatchedPages(0, 0, 0, emptyList())
    val firstMatched = matched.minOrNull() ?: pageCount + 1
    val lastMatched = matched.maxOrNull() ?: 0
    val prefix = missing.count { it < firstMatched }
    val suffix = missing.count { it > lastMatched }
    return UnmatchedPages(prefix, suffix, missing.size - prefix - suffix, toRanges(missing))
  }

  private fun toRanges(pages: List<Int>): List<String> {
    if (pages.isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    var start = pages.first()
    var end = start
    pages.drop(1).forEach { page ->
      if (page == end + 1) {
        end = page
      } else {
        result += if (start == end) "$start" else "$start-$end"
        start = page
        end = page
      }
    }
    result += if (start == end) "$start" else "$start-$end"
    return result
  }

  private data class Classification(
    val type: DedupRelationType,
    val containedBookId: String? = null,
    val containerBookId: String? = null,
  )

  private enum class MatchKind(
    val score: Int,
  ) {
    EXACT(4),
    PERCEPTUAL(1),
  }
}
