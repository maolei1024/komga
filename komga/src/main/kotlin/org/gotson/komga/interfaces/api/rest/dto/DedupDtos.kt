package org.gotson.komga.interfaces.api.rest.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupEligibilityReport
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupRelationStatus
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionMemberState
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.model.DedupResolutionState
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
  val coverCandidateDistance: Int,
  val coverTopK: Int,
) {
  constructor(value: DedupLibrarySettings) : this(
    value.libraryId,
    value.enabled,
    value.paused,
    value.scanInterval,
    value.batchSize,
    value.maxDurationSeconds,
    value.quietPeriodSeconds,
    value.coverCandidateDistance,
    value.coverTopK,
  )
}

data class DedupSettingsUpdateDto(
  @field:NotEmpty @field:Valid val libraries: List<DedupLibrarySettingsUpdateDto>,
)

data class DedupLibrarySettingsUpdateDto(
  @field:NotBlank val libraryId: String,
  val enabled: Boolean,
  val paused: Boolean = false,
  val scanInterval: Library.ScanInterval = Library.ScanInterval.DAILY,
  @field:Min(1) val batchSize: Int = 100,
  @field:Min(1) val maxDurationSeconds: Int = 300,
  @field:Min(0) val quietPeriodSeconds: Int = 180,
  @field:Min(0) @field:Max(256) val coverCandidateDistance: Int = 15,
  @field:Min(1) val coverTopK: Int = 20,
)

data class DedupLibrarySelectionDto(
  val libraryIds: Set<String> = emptySet(),
)

data class DedupScanResultDto(
  val requestedLibraries: Int,
)

data class DedupStatusDto(
  val work: Map<String, Int>,
  val clusters: Map<String, Int>,
  val resolutions: Map<String, Int>,
  val gorseSync: Map<String, Int>,
  val enabledLibraries: Int,
  val pausedLibraries: Int,
)

enum class DedupEvidenceMaturity {
  COVER_ONLY,
  PARTIAL,
  COMPLETE,
}

data class DedupClusterSummaryDto(
  val id: String,
  val libraryId: String,
  val revision: Long,
  val status: DedupClusterStatus,
  val reviewable: Boolean,
  val memberCount: Int,
  val coverMembers: List<DedupClusterCoverMemberDto>,
  val verifiedPairs: Int,
  val totalPairs: Int,
  val evidenceMaturity: DedupEvidenceMaturity,
  val suggestionPlanAvailable: Boolean,
  val suggestedPlanEligible: Boolean,
  val suggestedKeepCount: Int,
  val suggestedDeleteCount: Int,
  val reopenReason: String?,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") val lastModified: LocalDateTime,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") val processed: LocalDateTime?,
)

data class DedupClusterCoverMemberDto(
  val bookId: String,
  val title: String?,
  val thumbnailUrl: String,
)

data class DedupClusterDetailDto(
  val summary: DedupClusterSummaryDto,
  val stateRevision: String,
  val members: List<DedupClusterMemberDto>,
  val relations: List<DedupRelationDto>,
  val suggestedPlan: DedupPlanDto?,
  val eligibility: DedupEligibilityReport,
  val lastResolution: DedupResolutionSummaryDto?,
)

data class DedupClusterMemberDto(
  val bookId: String,
  val seriesId: String?,
  val book: BookDto?,
  val title: String?,
  val path: String?,
  val fileSize: Long?,
  val pageCount: Int?,
  val activeBookCountInSeries: Int,
  val inMvpScope: Boolean,
  val localStateReasonCodes: Set<String>,
  val localState: Map<String, Any>,
  val thumbnailUrl: String,
)

data class DedupRelationDto(
  val id: String,
  val leftBookId: String,
  val rightBookId: String,
  val type: DedupRelationType,
  val status: DedupRelationStatus,
  val coverDistance: Int?,
  val containedBookId: String?,
  val containerBookId: String?,
  val coverageLeft: Double?,
  val coverageRight: Double?,
  val orderConsistency: Double?,
  val longestMatchedRun: Int?,
  val unmatchedPrefixCount: Int?,
  val unmatchedSuffixCount: Int?,
  val unmatchedInternalCount: Int?,
  val confidence: Double?,
  val evidence: JsonNode?,
)

data class DedupPlanDto(
  val revision: String,
  val keepCount: Int,
  val deleteCount: Int,
  val members: List<DedupPlanMemberDto>,
)

data class DedupPlanMemberDto(
  val bookId: String,
  val action: DedupResolutionAction,
  val keeperBookId: String?,
  val directRelationId: String?,
)

data class DedupBulkVerificationRequestDto(
  @field:Size(min = 1, max = 100) @field:Valid val clusters: List<DedupClusterVerificationRequestDto>,
)

data class DedupClusterVerificationRequestDto(
  @field:NotBlank val clusterId: String,
  @field:Min(1) val expectedRevision: Long,
)

data class DedupSingleVerificationRequestDto(
  @field:Min(1) val expectedRevision: Long,
)

data class DedupBulkVerificationResultDto(
  val requestedClusters: Int,
  val queuedClusters: Int,
  val staleClusters: Int,
  val failedClusters: Int,
  val queuedPairs: Int,
  val skippedPairs: Int,
  val failedPairs: Int,
  val results: List<DedupClusterVerificationResultDto>,
)

data class DedupClusterVerificationResultDto(
  val clusterId: String,
  val status: String,
  val memberCount: Int,
  val pairCount: Int,
  val queuedPairs: Int,
  val skippedPairs: Int,
  val failedPairs: Int,
)

data class DedupSuggestedResolutionRequestDto(
  @field:Min(1) val expectedRevision: Long,
  @field:NotBlank val stateRevision: String,
  @field:NotBlank val planRevision: String,
)

data class DedupCustomResolutionRequestDto(
  @field:Min(1) val expectedRevision: Long,
  @field:NotBlank val stateRevision: String,
  @field:Size(min = 1) @field:Valid val members: List<DedupCustomResolutionMemberDto>,
  val acknowledgedReasonCodes: List<String> = emptyList(),
)

data class DedupCustomResolutionMemberDto(
  @field:NotBlank val bookId: String,
  val action: DedupResolutionAction,
  val keeperBookId: String? = null,
)

data class DedupResolutionDto(
  val id: String,
  val clusterId: String,
  val clusterRevision: Long,
  val mode: DedupResolutionMode,
  val planRevision: String,
  val state: DedupResolutionState,
  val members: List<DedupResolutionMemberDto>,
  val result: JsonNode?,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") val created: LocalDateTime,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") val lastModified: LocalDateTime,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") val completed: LocalDateTime?,
)

data class DedupResolutionSummaryDto(
  val id: String,
  val mode: DedupResolutionMode,
  val state: DedupResolutionState,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") val created: LocalDateTime,
  @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'") val completed: LocalDateTime?,
)

data class DedupResolutionMemberDto(
  val bookId: String,
  val seriesId: String,
  val action: DedupResolutionAction,
  val keeperBookId: String?,
  val title: String,
  val path: String,
  val expectedSize: Long?,
  val expectedArchiveHash: String?,
  val state: DedupResolutionMemberState,
  val resultCode: String?,
  val result: JsonNode?,
  val lastError: String?,
)

data class DedupPageComparisonDto(
  val leftBookId: String,
  val rightBookId: String,
  val relationType: DedupRelationType,
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

data class DedupConflictDto(
  val code: String,
  val message: String,
  val resolutionId: String?,
  val clusterState: DedupClusterStatus?,
  val partial: Boolean,
  val resolution: DedupResolutionDto?,
)
