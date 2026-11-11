package org.gotson.komga.domain.model

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.LocalDateTime

enum class MetadataEnrichmentProcessor {
  AI_TITLE,
  METADATA,
}

enum class MetadataEnrichmentStatus {
  WAITING,
  RUNNING,
  FAILED,
  STALE,
  SUCCESS,
}

enum class MetadataEnrichmentDictionaryUpdatePolicy {
  MARK_STALE,
  AUTO_LOW_PRIORITY,
}

data class MetadataEnrichmentBucket(
  val min: Int,
  val max: Int?,
  val label: String,
)

data class MetadataEnrichmentState(
  val bookId: String,
  val processor: MetadataEnrichmentProcessor,
  val status: MetadataEnrichmentStatus,
  val revision: Long,
  val inputHash: String,
  val inputJson: String,
  val resultJson: String? = null,
  val resultRevision: Long? = null,
  val lastError: String? = null,
  val startedDate: LocalDateTime? = null,
  val completedDate: LocalDateTime? = null,
  val createdDate: LocalDateTime = LocalDateTime.now(),
  val lastModifiedDate: LocalDateTime = createdDate,
)

data class MetadataEnrichmentSourceTag(
  val type: String,
  val value: String,
) {
  @get:JsonIgnore
  val normalizedType: String = type.trim().lowercase()

  @get:JsonIgnore
  val normalizedValue: String = value.trim().lowercase()

  @get:JsonIgnore
  val key: String = "$normalizedType:$normalizedValue"
}
