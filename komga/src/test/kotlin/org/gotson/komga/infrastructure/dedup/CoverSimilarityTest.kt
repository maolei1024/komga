package org.gotson.komga.infrastructure.dedup

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupFeatureState
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import javax.imageio.ImageIO

class CoverSimilarityTest {
  private val hasher = CoverPerceptualHasher()

  @Test
  fun `dHash is stable across image scaling and detects a changed composition`() {
    val original = image(800, 1200, false)
    val scaled = image(200, 300, false)
    val changed = image(800, 1200, true)

    val originalHash = hasher.hash(original).hash
    val scaledHash = hasher.hash(scaled).hash
    val changedHash = hasher.hash(changed).hash

    assertThat(hasher.distance(originalHash, scaledHash)).isLessThanOrEqualTo(2)
    assertThat(hasher.distance(originalHash, changedHash)).isGreaterThan(2)
  }

  @Test
  fun `pairwise neighbors do not turn a similarity bridge into a transitive cluster`() {
    val a = ByteArray(32)
    val b = a.copyOf().also { it[0] = 0b10000000.toByte() }
    val c = b.copyOf().also { it[31] = 0b00000001.toByte() }
    val index = CoverSimilarityIndex(hasher)
    index.replaceLibrary("library", listOf(feature("A", a), feature("B", b), feature("C", c)), threshold = 1)

    assertThat(index.findAllNeighbors("library", topK = 1))
      .extracting("bookLowId", "bookHighId", "distance")
      .containsExactlyInAnyOrder(
        org.assertj.core.groups.Tuple
          .tuple("A", "B", 1),
        org.assertj.core.groups.Tuple
          .tuple("B", "C", 1),
      )
  }

  private fun feature(
    id: String,
    hash: ByteArray,
  ) = DedupFeature(
    bookId = id,
    seriesId = id,
    libraryId = "library",
    sourceContentGeneration = "content-$id",
    sourceCoverGeneration = "cover-$id",
    sourceMetadataGeneration = "metadata-$id",
    seriesScopeRevision = "scope-$id",
    featureSchemaVersion = 1,
    coverState = DedupFeatureState.READY,
    coverSource = "TEST",
    coverHash = hash,
    coverQuality = 100,
    pageCount = 1,
    analyzedDate = LocalDateTime.now(),
    lastModifiedDate = LocalDateTime.now(),
  )

  private fun image(
    width: Int,
    height: Int,
    changed: Boolean,
  ): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, width, height)
    graphics.color = Color.BLACK
    if (changed) {
      graphics.fillRect(0, 0, width / 2, height)
    } else {
      graphics.fillRect(width / 4, height / 4, width / 2, height / 2)
    }
    graphics.dispose()
    return ByteArrayOutputStream().use {
      ImageIO.write(image, "png", it)
      it.toByteArray()
    }
  }
}
