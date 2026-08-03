package org.gotson.komga.interfaces.api.rest.dto

import com.fasterxml.jackson.annotation.JsonFormat
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import org.gotson.komga.domain.model.DedupDecisionItemState
import org.gotson.komga.domain.model.DedupDecisionMode
import org.gotson.komga.domain.model.DedupDecisionState
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupReviewCaseOrigin
import org.gotson.komga.domain.model.DedupReviewCaseStatus
import org.gotson.komga.domain.model.Library
import java.time.LocalDateTime

data class DedupSettingsDto(
  val libraries: List<DedupLibrarySettingsDto>,
)

data class DedupLibrarySettingsDto(
  val libraryId: String,
  val enabled: Boolean,
  val paused: Boolean,
  val scanInterval: Library.ScanInterval,
  val batchSize: Int,
  val maxDurationSeconds: Int,
  val quietPeriodSeconds: Int,
  val completionStabilitySeconds: Int,
  val coverCandidateDistance: Int,
  val coverTopK: Int,
) {
  constructor(value: DedupLibrarySettings) : this(
    libraryId = value.libraryId,
    enabled = value.enabled,
    paused = value.paused,
    scanInterval = value.scanInterval,
    batchSize = value.batchSize,
    maxDurationSeconds = value.maxDurationSeconds,
    quietPeriodSeconds = value.quietPeriodSeconds,
    completionStabilitySeconds = value.completionStabilitySeconds,
    coverCandidateDistance = value.coverCandidateDistance,
    coverTopK = value.coverTopK,
  )
}

data class DedupSettingsUpdateDto(
  @field:NotEmpty
  @field:Valid
  val libraries: List<DedupLibrarySettingsUpdateDto>,
)

data class DedupLibrarySettingsUpdateDto(
  val libraryId: String,
  val enabled: Boolean,
  val paused: Boolean = false,
  val scanInterval: Library.ScanInterval = Library.ScanInterval.DAILY,
  @field:Min(1)
  val batchSize: Int = 100,
  @field:Min(1)
  val maxDurationSeconds: Int = 300,
  @field:Min(0)
  val quietPeriodSeconds: Int = 180,
  @field:Min(0)
  val completionStabilitySeconds: Int = 300,
  @field:Min(0)
  @field:Max(256)
  val coverCandidateDistance: Int = 15,
  @field:Min(1)
  val coverTopK: Int = 20,
)

data class DedupLibrarySelectionDto(
  val libraryIds: Set<String> = emptySet(),
)

data class DedupScanResultDto(
  val requestedLibraries: Int,
)

data class DedupKeeperUpdateDto(
  val bookId: String,
  @field:Min(1)
  val expectedRevision: Long,
)

data class DedupOverrideRequestDto(
  val type: String,
  val bookId: String? = null,
  @field:Min(1)
  val expectedRevision: Long,
  val reason: String? = null,
)

data class DedupStatusDto(
  val work: Map<String, Int>,
  val decisions: Map<String, Int>,
  val decisionItems: Map<String, Int>,
  val gorseSync: Map<String, Int>,
  val enabledLibraries: Int,
  val pausedLibraries: Int,
  val reviewCases: Int,
  val exactFileCases: Int,
)

data class DedupReviewCaseDto(
  val id: String,
  val libraryId: String,
  val revision: Long,
  val status: DedupReviewCaseStatus,
  val origin: DedupReviewCaseOrigin,
  val relationType: String,
  val coverDistance: Int?,
  val coverageLeft: Double?,
  val coverageRight: Double?,
  val longestMatchedRun: Int?,
  val unmatchedPrefixCount: Int?,
  val unmatchedSuffixCount: Int?,
  val unmatchedInternalCount: Int?,
  val suggestedKeeperBookId: String?,
  val members: List<DedupReviewCaseMemberDto>,
  val eligibility: DedupEligibilityReportDto,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val created: LocalDateTime,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val lastModified: LocalDateTime,
)

data class DedupReviewCaseMemberDto(
  val book: BookDto?,
  val bookId: String,
  val activeBookCountInSeries: Int,
  val inMvpScope: Boolean,
)

data class DedupEligibilityReportDto(
  val suggestedPlanEligible: Boolean,
  val manualDeleteEligible: Boolean,
  val ruleVersion: Int,
  val stateRevision: String,
  val planRevision: String?,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val evaluatedAt: LocalDateTime,
  val blockers: List<DedupEligibilityReasonDto>,
  val warnings: List<DedupEligibilityReasonDto>,
  val passed: List<DedupEligibilityReasonDto>,
)

data class DedupEligibilityReasonDto(
  val code: String,
  val severity: DedupReasonSeverity,
  val appliesTo: Set<DedupAction>,
  val confirmationRequired: Boolean,
  val scope: String,
  val memberIds: Set<String> = emptySet(),
  val messageKey: String,
  val actual: Any? = null,
  val threshold: Any? = null,
  val pageRanges: List<String> = emptyList(),
  val action: String? = null,
)

enum class DedupReasonSeverity {
  BLOCKER,
  WARNING,
  PASSED,
}

enum class DedupAction {
  SUGGESTED,
  MANUAL,
}

data class DedupSuggestedDecisionRequestDto(
  @field:Min(1)
  val expectedRevision: Long,
  val stateRevision: String,
)

data class DedupCustomDecisionRequestDto(
  @field:Min(1)
  val expectedRevision: Long,
  val keeperBookId: String,
  @field:NotEmpty
  val removeBookIds: Set<String>,
  val stateRevision: String,
  val acknowledgedReasonCodes: Set<String> = emptySet(),
)

data class DedupDecisionDto(
  val id: String,
  val reviewCaseId: String?,
  val planRevision: String,
  val mode: DedupDecisionMode,
  val keeperBookId: String,
  val state: DedupDecisionState,
  val items: List<DedupDecisionItemDto>,
  val result: Any?,
  val gorseSyncState: String,
  val remoteConfirmationState: String,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val approved: LocalDateTime?,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val executed: LocalDateTime?,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val completed: LocalDateTime?,
)

data class DedupDecisionItemDto(
  val id: String,
  val bookId: String,
  val seriesId: String,
  val title: String,
  val path: String,
  val expectedSize: Long,
  val expectedArchiveHash: String,
  val state: DedupDecisionItemState,
  val attemptCount: Int,
  val resultCode: String?,
  val result: Any?,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
  val stabilityNotBefore: LocalDateTime?,
)

data class DedupPageComparisonDto(
  val relationType: String,
  val pages: Map<String, List<DedupPageEvidenceDto>>,
)

data class DedupPageEvidenceDto(
  val bookId: String,
  val pageNumber: Int,
  val matchedBookId: String?,
  val matchedPageNumber: Int?,
  val exactMatch: Boolean?,
  val thumbnailUrl: String,
)
