package org.gotson.komga.infrastructure.metadata.enrichment

import org.gotson.komga.domain.model.MetadataEnrichmentBucket

object MetadataEnrichmentBuckets {
  fun label(
    value: Int,
    buckets: List<MetadataEnrichmentBucket>,
  ): String =
    requireNotNull(buckets.firstOrNull { value >= it.min && (it.max == null || value <= it.max) }) {
      "No bucket configured for value $value"
    }.label.lowercase()

  fun labels(
    source: MetadataEnrichmentSource,
    settings: MetadataEnrichmentSettingsProvider,
  ): List<String> =
    buildList {
      if (source.pageCount > 0) add(label(source.pageCount, settings.pageSizeBuckets))
      source.contentTagCount?.let { add(label(it, settings.tagSizeBuckets)) }
    }
}
