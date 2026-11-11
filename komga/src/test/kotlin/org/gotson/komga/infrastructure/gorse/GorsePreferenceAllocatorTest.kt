package org.gotson.komga.infrastructure.gorse

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.gotson.komga.domain.model.MetadataEnrichmentBucket
import org.junit.jupiter.api.Test

class GorsePreferenceAllocatorTest {
  private val pages = listOf(MetadataEnrichmentBucket(1, 30, "pagesize_1-30"), MetadataEnrichmentBucket(31, null, "pagesize_31+"))
  private val tags = listOf(MetadataEnrichmentBucket(0, 20, "tagsize_0-20"), MetadataEnrichmentBucket(21, null, "tagsize_21+"))

  @Test
  fun `allocation is exact deterministic and includes the unbounded tail`() {
    val profile = PreferenceDistribution(5000, PreferenceAxis(30.0, 10.0), PreferenceAxis(20.0, 5.0))
    val first = GorsePreferenceAllocator.allocate(profile, pages, tags)
    assertThat(first.sum()).isEqualTo(5000)
    assertThat(first).allMatch { it > 0 }
    assertThat(GorsePreferenceAllocator.allocate(profile, pages, tags)).isEqualTo(first)
  }

  @Test
  fun `zero count is empty and invalid ranges are rejected`() {
    assertThat(GorsePreferenceAllocator.allocate(PreferenceDistribution(), pages, tags).sum()).isZero()
    assertThatIllegalArgumentException().isThrownBy {
      GorsePreferenceAllocator.allocate(PreferenceDistribution(1, PreferenceAxis(1.0, 0.0)), pages, tags)
    }
    assertThatIllegalArgumentException().isThrownBy {
      GorsePreferenceAllocator.allocate(PreferenceDistribution(5001), pages, tags)
    }
  }
}
