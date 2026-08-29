package org.gotson.komga.interfaces.api.rest.dto

import com.fasterxml.jackson.databind.JsonNode
import org.gotson.komga.domain.model.MetadataEnrichmentBucket
import org.gotson.komga.domain.model.MetadataEnrichmentDictionaryUpdatePolicy
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import java.time.LocalDateTime

data class MetadataEnrichmentSettingsDto(
  val aiEnabled: Boolean,
  val aiAutoOnNew: Boolean,
  val aiBaseUrl: String,
  val aiModel: String,
  val aiPrompt: String,
  val apiKeyConfigured: Boolean,
  val aiTimeoutSeconds: Int,
  val aiMaxRetries: Int,
  val dictionaryUpdatePolicy: MetadataEnrichmentDictionaryUpdatePolicy,
  val pageSizeBuckets: List<MetadataEnrichmentBucket>,
  val tagSizeBuckets: List<MetadataEnrichmentBucket>,
  val baseDictionaryEntryCount: Int,
  val overrideEntryCount: Int,
  val dictionaryFingerprint: String,
)

data class MetadataEnrichmentSettingsUpdateDto(
  val aiEnabled: Boolean? = null,
  val aiAutoOnNew: Boolean? = null,
  val aiBaseUrl: String? = null,
  val aiModel: String? = null,
  val aiPrompt: String? = null,
  val aiApiKey: String? = null,
  val clearAiApiKey: Boolean? = null,
  val aiTimeoutSeconds: Int? = null,
  val aiMaxRetries: Int? = null,
  val dictionaryUpdatePolicy: MetadataEnrichmentDictionaryUpdatePolicy? = null,
  val pageSizeBuckets: List<MetadataEnrichmentBucket>? = null,
  val tagSizeBuckets: List<MetadataEnrichmentBucket>? = null,
)

data class MetadataEnrichmentStatusCountDto(
  val processor: MetadataEnrichmentProcessor,
  val status: MetadataEnrichmentStatus,
  val count: Long,
)

data class MetadataEnrichmentStateDto(
  val bookId: String,
  val bookName: String,
  val bookTitle: String,
  val seriesId: String,
  val libraryId: String,
  val processor: MetadataEnrichmentProcessor,
  val status: MetadataEnrichmentStatus,
  val revision: Long,
  val resultRevision: Long?,
  val hasResult: Boolean,
  val lastError: String?,
  val startedDate: LocalDateTime?,
  val completedDate: LocalDateTime?,
  val lastModifiedDate: LocalDateTime,
)

data class MetadataEnrichmentRunRequestDto(
  val processor: MetadataEnrichmentProcessor,
  val bookIds: Set<String>? = null,
  val status: MetadataEnrichmentStatus? = null,
  val libraryId: String? = null,
)

data class MetadataEnrichmentRunResultDto(
  val accepted: Int,
)

data class MetadataEnrichmentDictionaryResultDto(
  val baseDictionaryEntryCount: Int,
  val overrideEntryCount: Int,
  val dictionaryFingerprint: String,
  val invalidatedBooks: Int,
)

data class MetadataEnrichmentOverrideDto(
  val k: String,
  val v: String,
  val t: String = "tag",
  val n: JsonNode? = null,
)

data class MetadataEnrichmentMissingTagDto(
  val type: String,
  val value: String,
  val bookCount: Int,
)
