package org.gotson.komga.infrastructure.dedup

import org.springframework.stereotype.Service
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.min

data class CoverHashResult(
  val hash: ByteArray,
  val quality: Int,
)

@Service
class CoverPerceptualHasher {
  fun hash(imageBytes: ByteArray): CoverHashResult {
    val source = requireNotNull(ImageIO.read(imageBytes.inputStream())) { "Unsupported cover image" }
    val normalized = BufferedImage(17, 16, BufferedImage.TYPE_BYTE_GRAY)
    normalized.createGraphics().let { graphics ->
      graphics.color = Color.WHITE
      graphics.fillRect(0, 0, normalized.width, normalized.height)
      graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
      graphics.drawImage(source, 0, 0, normalized.width, normalized.height, null)
      graphics.dispose()
    }

    val result = ByteArray(32)
    var bit = 0
    for (y in 0 until normalized.height) {
      for (x in 0 until normalized.width - 1) {
        val left = normalized.raster.getSample(x, y, 0)
        val right = normalized.raster.getSample(x + 1, y, 0)
        if (left > right) {
          val byteIndex = bit / 8
          result[byteIndex] = (result[byteIndex].toInt() or (1 shl (7 - bit % 8))).toByte()
        }
        bit++
      }
    }

    val quality = min(100, min(source.width, source.height) * 100 / 512)
    return CoverHashResult(result, quality)
  }

  fun distance(
    left: ByteArray,
    right: ByteArray,
  ): Int {
    require(left.size == 32 && right.size == 32) { "Cover hashes must be 256 bits" }
    return left.indices.sumOf { index ->
      Integer.bitCount((left[index].toInt() xor right[index].toInt()) and 0xff)
    }
  }
}
