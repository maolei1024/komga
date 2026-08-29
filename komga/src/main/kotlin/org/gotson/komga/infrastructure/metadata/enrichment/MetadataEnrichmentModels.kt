package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import org.gotson.komga.domain.model.MetadataEnrichmentSourceTag

data class MetadataEnrichmentSource(
  val galleryId: Long,
  val sourceRevision: String,
  val originalTitle: String,
  val originalSeries: String,
  val summary: String,
  val pageCount: Int,
  val tags: List<MetadataEnrichmentSourceTag>,
  val existingTags: Set<String>,
  val legacyProcessed: Boolean,
)

data class MetadataEnrichmentAiResult(
  val title: String? = null,
)

data class MetadataEnrichmentTagResult(
  val mapping: Map<String, String> = emptyMap(),
  val exactTags: Set<String> = emptySet(),
)

data class MetadataEnrichmentBucketResult(
  val label: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EhtagsDictionaryEntry(
  val k: String = "",
  val v: String = "",
  val t: String = "tag",
  val n: JsonNode? = null,
)

data class MetadataEnrichmentMissingTag(
  val type: String,
  val value: String,
  val bookCount: Int,
)
