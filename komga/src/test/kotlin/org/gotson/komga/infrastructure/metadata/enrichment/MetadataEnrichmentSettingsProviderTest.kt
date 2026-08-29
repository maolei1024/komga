package org.gotson.komga.infrastructure.metadata.enrichment

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.MetadataEnrichmentBucket
import org.junit.jupiter.api.Test

class MetadataEnrichmentSettingsProviderTest {
  @Test
  fun `default bucket configurations are valid and preserve existing labels`() {
    assertThatCode {
      MetadataEnrichmentSettingsProvider.validateBuckets(MetadataEnrichmentSettingsProvider.DEFAULT_PAGE_SIZE_BUCKETS, 1, "pageSize_")
      MetadataEnrichmentSettingsProvider.validateBuckets(MetadataEnrichmentSettingsProvider.DEFAULT_TAG_SIZE_BUCKETS, 0, "tagSize_")
    }.doesNotThrowAnyException()
  }

  @Test
  fun `bucket configurations must be continuous complete and uniquely labelled`() {
    assertThatThrownBy {
      MetadataEnrichmentSettingsProvider.validateBuckets(
        listOf(
          MetadataEnrichmentBucket(1, 10, "pageSize_same"),
          MetadataEnrichmentBucket(12, null, "pageSize_same"),
        ),
        1,
        "pageSize_",
      )
    }.isInstanceOf(IllegalArgumentException::class.java)
  }
}
