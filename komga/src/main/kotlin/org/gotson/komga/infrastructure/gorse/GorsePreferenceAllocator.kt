package org.gotson.komga.infrastructure.gorse

import org.gotson.komga.domain.model.MetadataEnrichmentBucket
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor

object GorsePreferenceAllocator {
  fun allocate(
    distribution: PreferenceDistribution,
    pages: List<MetadataEnrichmentBucket>,
    tags: List<MetadataEnrichmentBucket>,
  ): List<Int> {
    require(distribution.count in 0..5000) { "Sample count must be between 0 and 5000" }
    listOf(distribution.pages, distribution.tags).forEach {
      require(it.mean.isFinite() && it.deviation.isFinite() && it.deviation > 0) { "Curve center must be finite and deviation must be positive" }
    }
    require(distribution.pages.mean >= 1 && distribution.tags.mean >= 0) { "Pages must be positive and tags cannot be negative" }
    val weights = pages.flatMap { page -> tags.map { tag -> mass(page, distribution.pages) * mass(tag, distribution.tags) } }
    if (distribution.count == 0) return weights.map { 0 }
    val total = weights.sum()
    require(total > 0 && total.isFinite()) { "The curves do not cover the configured buckets" }
    val expected = weights.map { it / total * distribution.count }
    val result = expected.map { floor(it).toInt() }.toMutableList()
    expected.indices
      .sortedWith(compareByDescending<Int> { expected[it] - result[it] }.thenBy { it })
      .take(distribution.count - result.sum())
      .forEach { result[it]++ }
    return result
  }

  private fun mass(
    bucket: MetadataEnrichmentBucket,
    axis: PreferenceAxis,
  ): Double {
    val low = cdf((bucket.min - 0.5 - axis.mean) / axis.deviation)
    val high = bucket.max?.let { cdf((it + 0.5 - axis.mean) / axis.deviation) } ?: 1.0
    return (high - low).coerceAtLeast(0.0)
  }

  private fun cdf(value: Double): Double {
    val factor = 1.0 / (1.0 + 0.2316419 * abs(value))
    val polynomial = factor * (0.319381530 + factor * (-0.356563782 + factor * (1.781477937 + factor * (-1.821255978 + factor * 1.330274429))))
    val tail = 0.3989422804014327 * exp(-value * value / 2.0) * polynomial
    return if (value >= 0) 1 - tail else tail
  }
}
