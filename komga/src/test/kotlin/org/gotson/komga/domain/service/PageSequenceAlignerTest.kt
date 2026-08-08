package org.gotson.komga.domain.service

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupPageFeature
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.infrastructure.dedup.CoverPerceptualHasher
import org.junit.jupiter.api.Test

class PageSequenceAlignerTest {
  private val aligner = PageSequenceAligner(CoverPerceptualHasher())

  @Test
  fun `identical ordered page hashes are the same page sequence`() {
    val result = aligner.align("left", pages("left", 1..5), "right", pages("right", 1..5))

    assertThat(result.relationType).isEqualTo(DedupRelationType.SAME_PAGE_SEQUENCE)
    assertThat(result.coverageLeft).isEqualTo(1.0)
    assertThat(result.longestMatchedRun).isEqualTo(5)
    assertThat(result.matches).allMatch { it.exact && it.perceptualDistance == null }
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
  fun `an unmatched page in an equal length sequence remains ambiguous and has no direction`() {
    val left = pages("left", 1..10).toMutableList().also { it[4] = page("left", 5, "unique", ByteArray(32) { 0xff.toByte() }) }
    val right = pages("right", 1..10)

    val result = aligner.align("left", left, "right", right)

    assertThat(result.relationType).isEqualTo(DedupRelationType.AMBIGUOUS)
    assertThat(result.unmatchedLeft.internalCount).isEqualTo(1)
    assertThat(result.unmatchedLeft.ranges).containsExactly("5")
    assertThat(result.containedBookId).isNull()
    assertThat(result.containerBookId).isNull()
  }

  @Test
  fun `perceptually equal pages with different exact hashes are the same page sequence`() {
    val hash = ByteArray(32)
    val left = (1..5).map { page("left", it, "left-$it", hash) }
    val right = (1..5).map { page("right", it, "right-$it", hash) }

    val result = aligner.align("left", left, "right", right)

    assertThat(result.relationType).isEqualTo(DedupRelationType.SAME_PAGE_SEQUENCE)
    assertThat(result.matches).allMatch { !it.exact && it.perceptualDistance == 0 }
  }

  @Test
  fun `a complete diagonal sequence with one perceptual page is the same page sequence`() {
    val left = pages("left", 1..5)
    val right = pages("right", 1..5).toMutableList().also { it[2] = page("right", 3, "variant", left[2].perceptualHash!!) }

    val result = aligner.align("left", left, "right", right)

    assertThat(result.relationType).isEqualTo(DedupRelationType.SAME_PAGE_SEQUENCE)
    assertThat(result.matches.count { it.exact }).isEqualTo(4)
    assertThat(result.matches.single { !it.exact }.perceptualDistance).isZero()
  }

  @Test
  fun `perceptual distance at the threshold is recorded and accepted`() {
    val leftHash = ByteArray(32)
    val rightHash = ByteArray(32).also { it[0] = 0xff.toByte() }

    val result =
      aligner.align(
        "left",
        listOf(page("left", 1, "left", leftHash)),
        "right",
        listOf(page("right", 1, "right", rightHash)),
      )

    assertThat(result.relationType).isEqualTo(DedupRelationType.SAME_PAGE_SEQUENCE)
    assertThat(result.matches.single().perceptualDistance).isEqualTo(8)
  }

  @Test
  fun `perceptual distance above the threshold is not matched`() {
    val leftHash = ByteArray(32)
    val rightHash =
      ByteArray(32).also {
        it[0] = 0xff.toByte()
        it[1] = 0x01
      }

    val result =
      aligner.align(
        "left",
        listOf(page("left", 1, "left", leftHash)),
        "right",
        listOf(page("right", 1, "right", rightHash)),
      )

    assertThat(result.relationType).isEqualTo(DedupRelationType.NO_MATCH)
    assertThat(result.matches).isEmpty()
  }

  @Test
  fun `exact match ratio does not gate a complete perceptual sequence`() {
    val left = pages("left", 1..22)
    val right =
      left.map { source ->
        if (source.pageNumber <= 7) {
          page("right", source.pageNumber, source.exactHash!!, source.perceptualHash!!)
        } else {
          page("right", source.pageNumber, "variant-${source.pageNumber}", source.perceptualHash!!)
        }
      }

    val result = aligner.align("left", left, "right", right)

    assertThat(result.relationType).isEqualTo(DedupRelationType.SAME_PAGE_SEQUENCE)
    assertThat(result.matches.count { it.exact }).isEqualTo(7)
    assertThat(result.matches.count { !it.exact }).isEqualTo(15)
  }

  @Test
  fun `equal page counts with an off diagonal alignment remain ambiguous`() {
    val far = ByteArray(32) { 0xff.toByte() }
    val left = listOf(page("left", 1, "A", far), page("left", 2, "B", far), page("left", 3, "C", far))
    val right = listOf(page("right", 1, "unique", ByteArray(32)), page("right", 2, "A", far), page("right", 3, "B", far))

    val result = aligner.align("left", left, "right", right)

    assertThat(result.relationType).isEqualTo(DedupRelationType.AMBIGUOUS)
    assertThat(result.matches.map { it.leftPage to it.rightPage }).containsExactly(1 to 2, 2 to 3)
  }

  @Test
  fun `overlap below thirty percent is cached as no match`() {
    val left = pages("left", 1..10)
    val right = listOf(page("right", 1, "hash-1")) + (2..10).map { page("right", it, "other-$it", ByteArray(32) { 0xff.toByte() }) }

    val result = aligner.align("left", left, "right", right)

    assertThat(result.coverageLeft).isEqualTo(0.1)
    assertThat(result.relationType).isEqualTo(DedupRelationType.NO_MATCH)
  }

  @Test
  fun `thirty percent overlap is ambiguous`() {
    val far = ByteArray(32) { 0xff.toByte() }
    val left = pages("left", 1..10)
    val right =
      (1..10).map { number ->
        if (number <= 3) page("right", number, "hash-$number") else page("right", number, "other-$number", far)
      }

    assertThat(aligner.align("left", left, "right", right).relationType).isEqualTo(DedupRelationType.AMBIGUOUS)
  }

  @Test
  fun `empty input has no page match`() {
    val result = aligner.align("left", emptyList(), "right", pages("right", 1..3))

    assertThat(result.relationType).isEqualTo(DedupRelationType.NO_MATCH)
    assertThat(result.unmatchedRight.total).isEqualTo(3)
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
