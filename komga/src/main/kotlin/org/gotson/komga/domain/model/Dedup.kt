package org.gotson.komga.domain.model

import java.time.LocalDateTime

data class DedupLibrarySettings(
  val libraryId: String,
  val enabled: Boolean = false,
  val paused: Boolean = false,
  val scanInterval: Library.ScanInterval = Library.ScanInterval.DAILY,
  val batchSize: Int = 100,
  val maxDurationSeconds: Int = 300,
  val quietPeriodSeconds: Int = 180,
  val completionStabilitySeconds: Int = 300,
  val coverCandidateDistance: Int = 15,
  val coverTopK: Int = 20,
  val createdDate: LocalDateTime = LocalDateTime.now(),
  val lastModifiedDate: LocalDateTime = createdDate,
) {
  init {
    require(scanInterval != Library.ScanInterval.DISABLED) { "Use enabled=false instead of a disabled interval" }
    require(batchSize > 0) { "Batch size must be positive" }
    require(maxDurationSeconds > 0) { "Maximum duration must be positive" }
    require(quietPeriodSeconds >= 0) { "Quiet period cannot be negative" }
    require(completionStabilitySeconds >= 0) { "Completion stability period cannot be negative" }
    require(coverCandidateDistance in 0..256) { "Cover candidate distance must be between 0 and 256" }
    require(coverTopK > 0) { "Cover top-K must be positive" }
  }
}

enum class DedupWorkType {
  RECONCILE_EXACT_DUPLICATES,
  COMPUTE_COVER,
  FIND_COVER_NEIGHBORS,
  VERIFY_RELATION,
  APPLY_DECISION_ITEM,
  VERIFY_DELETION,
}

enum class DedupWorkState {
  WAITING,
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED_REVIEW,
  CANCELLED,
}

data class DedupWork(
  val id: String,
  val libraryId: String,
  val type: DedupWorkType,
  val targetKey: String,
  val state: DedupWorkState,
  val desiredRevision: Long,
  val completedRevision: Long,
  val notBefore: LocalDateTime,
  val nextRetryAt: LocalDateTime?,
  val leaseOwner: String?,
  val leaseToken: String?,
  val leaseUntil: LocalDateTime?,
  val attemptCount: Int,
  val maxAttempts: Int,
  val lastErrorCode: String?,
  val lastError: String?,
  val priority: Int,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
  val completedDate: LocalDateTime?,
)

data class ExactDuplicateBook(
  val id: String,
  val seriesId: String,
  val libraryId: String,
  val name: String,
  val url: String,
  val fileHash: String,
  val fileSize: Long,
  val fileLastModified: LocalDateTime,
  val oneshot: Boolean,
  val deleted: Boolean,
)

data class DedupRelation(
  val id: String,
  val libraryId: String,
  val bookLowId: String,
  val bookHighId: String,
  val lowContentGeneration: String,
  val highContentGeneration: String,
  val lowCoverGeneration: String = "",
  val highCoverGeneration: String = "",
  val lowMetadataGeneration: String = "",
  val highMetadataGeneration: String = "",
  val type: DedupRelationType,
  val coverDistance: Int? = null,
  val containedBookId: String? = null,
  val containerBookId: String? = null,
  val coverageLeft: Double? = null,
  val coverageRight: Double? = null,
  val orderConsistency: Double? = null,
  val longestMatchedRun: Int? = null,
  val unmatchedPrefixCount: Int? = null,
  val unmatchedSuffixCount: Int? = null,
  val unmatchedInternalCount: Int? = null,
  val status: DedupRelationStatus = DedupRelationStatus.VERIFIED,
  val evidenceJson: String = "{}",
  val featureSchemaVersion: Int = 1,
  val classifierRuleVersion: Int = 1,
  val createdDate: LocalDateTime = LocalDateTime.now(),
  val lastModifiedDate: LocalDateTime = createdDate,
)

enum class DedupRelationType {
  EXACT_FILE,
  EXACT_PAGE_SEQUENCE,
  SAME_EDITION_VARIANT,
  CONTAINED_IN,
  NEAR_CONTAINED_IN,
  PARTIAL_OVERLAP,
  ALT_EDITION,
  EDITION_UNCERTAIN,
  VISUALLY_SIMILAR,
  UNRELATED,
}

enum class DedupRelationStatus {
  CANDIDATE,
  VERIFYING,
  VERIFIED,
  REJECTED,
  STALE,
  FAILED_REVIEW,
}

data class DedupFeature(
  val bookId: String,
  val seriesId: String,
  val libraryId: String,
  val sourceContentGeneration: String,
  val sourceCoverGeneration: String,
  val sourceMetadataGeneration: String,
  val seriesScopeRevision: String,
  val featureSchemaVersion: Int,
  val coverState: DedupFeatureState,
  val pageState: DedupFeatureState = DedupFeatureState.WAITING,
  val coverSource: String?,
  val coverHash: ByteArray?,
  val coverQuality: Int?,
  val pageCount: Int?,
  val analyzedDate: LocalDateTime?,
  val lastModifiedDate: LocalDateTime,
) {
  override fun equals(other: Any?): Boolean =
    other is DedupFeature &&
      bookId == other.bookId &&
      sourceContentGeneration == other.sourceContentGeneration &&
      sourceCoverGeneration == other.sourceCoverGeneration &&
      featureSchemaVersion == other.featureSchemaVersion &&
      coverHash.contentEqualsNullable(other.coverHash)

  override fun hashCode(): Int = 31 * bookId.hashCode() + (coverHash?.contentHashCode() ?: 0)
}

enum class DedupFeatureState {
  WAITING,
  PENDING,
  RUNNING,
  READY,
  STALE,
  FAILED_REVIEW,
  DELETED,
}

data class DedupPageFeature(
  val bookId: String,
  val sourceContentGeneration: String,
  val featureSchemaVersion: Int,
  val pageNumber: Int,
  val exactHash: String?,
  val perceptualHash: ByteArray?,
  val quality: Int?,
)

data class DedupSourceIdentity(
  val bookId: String,
  val seriesId: String,
  val libraryId: String,
  val contentGeneration: String,
  val coverGeneration: String,
  val metadataGeneration: String,
  val seriesScopeRevision: String,
  val pageCount: Int?,
)

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
  when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
  }

data class DedupReviewCase(
  val id: String,
  val libraryId: String,
  val revision: Long,
  val status: DedupReviewCaseStatus,
  val suggestedKeeperBookId: String?,
  val origin: DedupReviewCaseOrigin,
  val memberBookIds: Set<String>,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
)

data class DedupReviewCaseCandidate(
  val id: String,
  val libraryId: String,
  val origin: DedupReviewCaseOrigin,
  val memberBookIds: Set<String>,
  val relations: List<DedupRelation>,
)

enum class DedupReviewCaseStatus {
  REVIEW_REQUIRED,
  SUGGESTION_READY,
  COMPLETED,
  IGNORED,
  STALE,
  FAILED_REVIEW,
  NO_KEEPER,
}

enum class DedupReviewCaseOrigin {
  EXACT_FILE,
  COVER_SIMILARITY,
}

data class DedupOverride(
  val id: String,
  val type: DedupOverrideType,
  val bookLowId: String? = null,
  val bookHighId: String? = null,
  val bookId: String? = null,
  val lowContentGeneration: String? = null,
  val highContentGeneration: String? = null,
  val lowCoverGeneration: String? = null,
  val highCoverGeneration: String? = null,
  val actorId: String,
  val reason: String? = null,
  val createdDate: LocalDateTime = LocalDateTime.now(),
)

enum class DedupOverrideType {
  UNRELATED,
  NOT_VISUALLY_SIMILAR,
  NOT_CONTENT_DUPLICATE,
  ALT_EDITION,
  PROTECTED,
}
