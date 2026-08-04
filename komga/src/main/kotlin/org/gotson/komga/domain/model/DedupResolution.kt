package org.gotson.komga.domain.model

import java.time.LocalDateTime

enum class DedupResolutionMode {
  SUGGESTED,
  CUSTOM,
}

enum class DedupResolutionState {
  PROCESSING,
  PROCESSED,
  NEEDS_ATTENTION,
  PARTIALLY_COMPLETED,
}

enum class DedupResolutionAction {
  KEEP,
  DELETE,
}

enum class DedupResolutionMemberState {
  PLANNED,
  PREFLIGHTED,
  DELETED,
  KOMGA_SAVED,
  GORSE_CONFIRMED,
  COMPLETED,
  FAILED,
  CONFLICT,
}

enum class DedupDeletionResultCode {
  DELETED,
  ALREADY_DELETED_BY_THIS_RESOLUTION,
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
  KOMGA_NOT_SAVED,
  GORSE_FAILED,
  CONFIRMED,
}

data class DedupResolution(
  val id: String,
  val clusterId: String,
  val clusterRevision: Long,
  val mode: DedupResolutionMode,
  val planRevision: String,
  val planJson: String,
  val evidenceJson: String,
  val eligibilityJson: String,
  val ruleVersion: Int,
  val state: DedupResolutionState,
  val actorId: String,
  val resultJson: String,
  val leaseToken: String,
  val leaseUntil: LocalDateTime,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
  val completedDate: LocalDateTime?,
)

data class DedupResolutionMember(
  val resolutionId: String,
  val bookId: String,
  val seriesId: String,
  val libraryId: String,
  val action: DedupResolutionAction,
  val keeperBookId: String?,
  val titleSnapshot: String,
  val pathSnapshot: String,
  val sourceGenerationsJson: String,
  val localStateSnapshotJson: String,
  val directRelationId: String?,
  val directRelationSnapshotJson: String?,
  val expectedPath: String?,
  val expectedSize: Long?,
  val expectedMtime: LocalDateTime?,
  val expectedArchiveHash: String?,
  val state: DedupResolutionMemberState,
  val resultCode: String?,
  val resultJson: String?,
  val lastError: String?,
  val createdDate: LocalDateTime,
  val lastModifiedDate: LocalDateTime,
)

data class DedupPlanMember(
  val bookId: String,
  val action: DedupResolutionAction,
  val keeperBookId: String? = null,
  val directRelationId: String? = null,
)

data class DedupResolutionPlan(
  val revision: String,
  val members: List<DedupPlanMember>,
) {
  val deleteCount: Int get() = members.count { it.action == DedupResolutionAction.DELETE }
  val keepCount: Int get() = members.count { it.action == DedupResolutionAction.KEEP }
}

enum class DedupEligibilitySeverity {
  BLOCKER,
  WARNING,
  PASSED,
}

data class DedupEligibilityReason(
  val code: String,
  val severity: DedupEligibilitySeverity,
  val appliesTo: Set<String>,
  val confirmationRequired: Boolean,
  val scope: String,
  val memberIds: Set<String> = emptySet(),
  val actual: Any? = null,
  val threshold: Any? = null,
  val action: String? = null,
)

data class DedupEligibilityReport(
  val suggestionPlanAvailable: Boolean,
  val suggestionEvidenceEligible: Boolean,
  val processingEligible: Boolean,
  val suggestedPlanEligible: Boolean,
  val ruleVersion: Int,
  val stateRevision: String,
  val planRevision: String?,
  val evaluatedAt: LocalDateTime,
  val blockers: List<DedupEligibilityReason>,
  val warnings: List<DedupEligibilityReason>,
  val passed: List<DedupEligibilityReason>,
)
