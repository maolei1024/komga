package org.gotson.komga.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupPageFeature
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.infrastructure.dedup.CoverPerceptualHasher
import org.junit.jupiter.api.Test

class PageSequenceAlignerTest {
  private val aligner = PageSequenceAligner(CoverPerceptualHasher())

  @Test
  fun `identical ordered page hashes are exact page sequences`() {
    val result = aligner.align("left", pages("left", 1..5), "right", pages("right", 1..5))

    assertThat(result.relationType).isEqualTo(DedupRelationType.EXACT_PAGE_SEQUENCE)
    assertThat(result.coverageLeft).isEqualTo(1.0)
    assertThat(result.longestMatchedRun).isEqualTo(5)
  }

  @Test
  fun `an exact short sequence fully covered by a longer sequence is directed containment`() {
    val result = aligner.align("short", pages("short", 2..5), "long", pages("long", 1..6))

    assertThat(result.relationType).isEqualTo(DedupRelationType.CONTAINED_IN)
    assertThat(result.containedBookId).isEqualTo("short")
    assertThat(result.containerBookId).isEqualTo("long")
    assertThat(result.unmatchedLeft.total).isZero()
  }

  @Test
  fun `an unmatched page in the losing sequence can only be near containment`() {
    val left = pages("left", 1..10).toMutableList().also { it[4] = page("left", 5, "unique", ByteArray(32) { 0xff.toByte() }) }
    val right = pages("right", 1..10)

    val result = aligner.align("left", left, "right", right)

    assertThat(result.relationType).isEqualTo(DedupRelationType.NEAR_CONTAINED_IN)
    assertThat(result.unmatchedLeft.internalCount).isEqualTo(1)
    assertThat(result.unmatchedLeft.ranges).containsExactly("5")
  }

  @Test
  fun `perceptually equal pages with different exact hashes remain edition uncertain`() {
    val hash = ByteArray(32)
    val left = (1..5).map { page("left", it, "left-$it", hash) }
    val right = (1..5).map { page("right", it, "right-$it", hash) }

    val result = aligner.align("left", left, "right", right)

    assertThat(result.relationType).isEqualTo(DedupRelationType.EDITION_UNCERTAIN)
  }

  private fun pages(
    bookId: String,
    values: IntRange,
  ): List<DedupPageFeature> = values.map { page(bookId, it, "hash-$it") }

  private fun page(
    bookId: String,
    number: Int,
    exactHash: String,
    perceptualHash: ByteArray = ByteArray(32).also { it[0] = number.toByte() },
  ) = DedupPageFeature(
    bookId = bookId,
    sourceContentGeneration = "generation",
    featureSchemaVersion = 1,
    pageNumber = number,
    exactHash = exactHash,
    perceptualHash = perceptualHash,
    quality = 100,
  )
}
