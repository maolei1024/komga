package org.gotson.komga.domain.model

import java.time.LocalDateTime

enum class DedupDecisionMode {
  SUGGESTED,
  MANUAL,
}

enum class DedupDecisionState {
  DRAFT,
  APPROVED,
  REVALIDATING,
  PURGING,
  PARTIALLY_COMPLETED,
  REAPPROVAL_REQUIRED,
  COMPLETED,
  NEEDS_ATTENTION,
  ABORTED,
  FAILED,
}

enum class DedupDecisionItemState {
  PENDING,
  REVALIDATING,
  READY_TO_DELETE,
  DELETE_SUBMITTED,
  LOCAL_PATH_ABSENT,
  DB_SOFT_DELETED,
  CONFIRMED,
  REAPPEARED,
  FAILED,
  CONFLICT,
}

enum class DedupDeletionResultCode {
  DELETED,
  ALREADY_DELETED_BY_THIS_DECISION,
  PATH_MISSING_UNCONFIRMED,
  GENERATION_MISMATCH,
  STATE_CHANGED,
  RELATION_CHANGED,
  SCOPE_CHANGED,
  KEEPER_UNHEALTHY,
  NOT_WRITABLE,
  DELETE_FAILED,
  REAPPEARED_SAME_HASH,
  REAPPEARED_DIFFERENT_HASH,
  STABILITY_PENDING,
  CONFIRMED,
}

data class DedupDecision(
  val id: String,
  val reviewCaseId: String?,
  val planRevision: String,
  val mode: DedupDecisionMode,
  val keeperBookId: String,
  val keeperSnapshotJson: String,
  val planJson: String,
  val evidenceJson: String,
  val eligibilityJson: String,
  val classifierRuleVersion: Int,
  val manualConfirmationJson: String?,
  val state: DedupDecisionState,
  val actorId: String,
  val executionToken: String? = null,
  val leaseUntil: LocalDateTime? = null,
  val resultJson: String = "{}",
  val gorseSyncState: String = "PENDING",
  val remoteConfirmationState: String = "UNKNOWN",
  val approvedDate: LocalDateTime?,
  val executedDate: LocalDateTime?,
  val completedDate: LocalDateTime?,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
)

data class DedupDecisionItem(
  val id: String,
  val decisionId: String,
  val bookId: String,
  val seriesId: String,
  val libraryId: String,
  val titleSnapshot: String,
  val pathSnapshot: String,
  val expectedPath: String,
  val expectedSize: Long,
  val expectedMtime: LocalDateTime,
  val expectedArchiveHash: String,
  val sourceContentGeneration: String,
  val seriesScopeRevision: String,
  val stateRevision: String,
  val acknowledgedReasonsJson: String,
  val directRelationId: String,
  val directRelationGenerations: String,
  val state: DedupDecisionItemState,
  val attemptCount: Int,
  val resultCode: String?,
  val resultJson: String?,
  val lastError: String?,
  val stabilityNotBefore: LocalDateTime?,
  val deletedDate: LocalDateTime?,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
)

data class DedupGorseSync(
  val seriesId: String,
  val libraryId: String,
  val desiredHidden: Boolean,
  val state: String,
  val attemptCount: Int,
  val nextRetryAt: LocalDateTime?,
  val lastError: String?,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
  val completedDate: LocalDateTime?,
)

data class DedupLocalStateSnapshot(
  val bookId: String,
  val revision: String,
  val reasonCodes: Set<String>,
  val details: Map<String, Any>,
)
