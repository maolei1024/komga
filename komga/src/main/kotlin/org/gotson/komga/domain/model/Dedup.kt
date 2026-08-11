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
  val coverCandidateDistance: Int = 15,
  val coverTopK: Int = 20,
  val autoResolveSuggestions: Boolean = false,
  val createdDate: LocalDateTime = LocalDateTime.now(),
  val lastModifiedDate: LocalDateTime = createdDate,
  val lastBatchDate: LocalDateTime? = null,
  val lastBatchBookCount: Int = 0,
) {
  init {
    require(scanInterval != Library.ScanInterval.DISABLED) { "Use enabled=false instead of a disabled interval" }
    require(batchSize > 0) { "Batch size must be positive" }
    require(maxDurationSeconds > 0) { "Maximum duration must be positive" }
    require(quietPeriodSeconds >= 0) { "Quiet period cannot be negative" }
    require(coverCandidateDistance in 0..256) { "Cover candidate distance must be between 0 and 256" }
    require(coverTopK > 0) { "Cover top-K must be positive" }
    require(lastBatchBookCount >= 0) { "Last batch Book count cannot be negative" }
  }
}

enum class DedupWorkType {
  SCAN_BOOK,
  VERIFY_RELATION,
  REBUILD_CLUSTERS,
  AUTO_RESOLVE_SUGGESTIONS,
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
  val type: DedupRelationType,
  val coverDistance: Int? = null,
  val containedBookId: String? = null,
  val containerBookId: String? = null,
  val evidenceJson: String = "{}",
  val featureSchemaVersion: Int = 1,
  val classifierRuleVersion: Int = 1,
  val createdDate: LocalDateTime = LocalDateTime.now(),
  val lastModifiedDate: LocalDateTime = createdDate,
) {
  init {
    require(bookLowId < bookHighId) { "Dedup relation book IDs must use canonical order" }
  }
}

enum class DedupRelationType(
  val reviewable: Boolean,
) {
  COVER_CANDIDATE(false),
  EXACT_FILE(true),
  SAME_PAGE_SEQUENCE(true),
  CONTAINED_IN(true),
  AMBIGUOUS(true),
  NO_MATCH(false),
}

enum class DedupPairDecisionType {
  KEEP_BOTH,
}

data class DedupPairDecision(
  val bookLowId: String,
  val bookHighId: String,
  val decision: DedupPairDecisionType = DedupPairDecisionType.KEEP_BOTH,
  val resolutionId: String?,
  val actorId: String,
  val createdDate: LocalDateTime = LocalDateTime.now(),
) {
  init {
    require(bookLowId < bookHighId) { "Dedup pair decision Book IDs must use canonical order" }
  }
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
  val archiveHash: String? = null,
  val archiveHashPath: String? = null,
  val archiveHashSize: Long? = null,
  val archiveHashSchemaVersion: Int? = null,
  val archiveHashDate: LocalDateTime? = null,
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

enum class DedupArchiveHashState {
  MISSING,
  READY,
  STALE,
}

const val DEDUP_ARCHIVE_HASH_SCHEMA_VERSION = 1

fun dedupContentGeneration(
  fileSize: Long,
  archiveHash: String?,
  sourceFingerprint: String? = null,
): String = "dedup-v2:$fileSize:${sourceFingerprint?.takeIf(String::isNotBlank) ?: "UNHASHED"}:${archiveHash ?: "UNHASHED"}"

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
  val archiveHashState: DedupArchiveHashState = DedupArchiveHashState.MISSING,
  val archiveHash: String? = null,
)

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
  when {
    this == null -> other == null
    other == null -> false
    else -> contentEquals(other)
  }

enum class DedupClusterStatus {
  UNPROCESSED,
  PROCESSING,
  PROCESSED,
  NEEDS_ATTENTION,
}

enum class DedupEvidenceMaturity {
  COVER_ONLY,
  PARTIAL,
  COMPLETE,
}

data class DedupCluster(
  val id: String,
  val libraryId: String,
  val revision: Long,
  val status: DedupClusterStatus,
  val reviewable: Boolean,
  val anchorBookId: String,
  val topologyFingerprint: String,
  val evidenceFingerprint: String,
  val stateFingerprint: String,
  val processedRevision: Long?,
  val lastResolutionId: String?,
  val reopenReason: String?,
  val supersededBy: String?,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
  val processedDate: LocalDateTime?,
  val memberCount: Int = 0,
  val verifiedPairCount: Int = 0,
  val totalPairCount: Int = 0,
  val evidenceMaturity: DedupEvidenceMaturity = DedupEvidenceMaturity.COVER_ONLY,
)

data class DedupClusterMember(
  val clusterId: String,
  val bookId: String,
  val present: Boolean,
  val sourceContentGeneration: String,
  val sourceCoverGeneration: String,
  val sourceMetadataGeneration: String,
  val seriesScopeRevision: String,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
)

data class DedupClusterWithMembers(
  val cluster: DedupCluster,
  val members: List<DedupClusterMember>,
)

data class DedupGorseSync(
  val seriesId: String,
  val libraryId: String,
  val desiredHidden: Boolean,
  val state: String,
  val revision: Long,
  val attemptCount: Int,
  val nextRetryAt: LocalDateTime?,
  val lastError: String?,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
  val completedDate: LocalDateTime?,
)
