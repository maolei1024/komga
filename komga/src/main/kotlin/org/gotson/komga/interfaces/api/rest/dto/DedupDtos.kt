package org.gotson.komga.interfaces.api.rest.dto

import com.fasterxml.jackson.databind.JsonNode
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import org.gotson.komga.domain.model.DedupRelationStatus
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.model.Library
import java.time.LocalDateTime

data class DedupLibrarySettingsDto(
  val libraryId: String,
  val libraryName: String,
  val enabled: Boolean,
  val paused: Boolean,
  val scanInterval: Library.ScanInterval,
  val batchSize: Int,
  val maxDurationSeconds: Int,
  val quietPeriodSeconds: Int,
  val coverCandidateDistance: Int,
  val coverTopK: Int,
  val lastBatchDate: LocalDateTime?,
  val lastBatchBookCount: Int,
)

data class DedupSettingsDto(
  val libraries: List<DedupLibrarySettingsDto>,
)

data class DedupLibrarySettingsUpdateDto(
  @field:NotBlank val libraryId: String,
  val enabled: Boolean,
  val paused: Boolean = false,
  val scanInterval: Library.ScanInterval,
  @field:Min(1) @field:Max(10_000) val batchSize: Int,
  @field:Min(1) @field:Max(86_400) val maxDurationSeconds: Int = 300,
  @field:Min(0) @field:Max(86_400) val quietPeriodSeconds: Int = 180,
  @field:Min(0) @field:Max(256) val coverCandidateDistance: Int,
  @field:Min(1) @field:Max(1_000) val coverTopK: Int,
)

data class DedupSettingsUpdateDto(
  @field:Valid @field:NotEmpty val libraries: List<DedupLibrarySettingsUpdateDto>,
)

data class DedupLibraryRunStatusDto(
  val libraryId: String,
  val libraryName: String,
  val lastBatchDate: LocalDateTime?,
  val lastBatchBookCount: Int,
  val nextBatchDate: LocalDateTime?,
)

data class DedupStatusDto(
  val pendingScanBooks: Int,
  val automaticVerificationPairs: Int,
  val unresolvedClusters: Long,
  val processedResolutions: Long,
  val enabledLibraries: Int,
  val libraries: List<DedupLibraryRunStatusDto>,
)

data class DedupLibrarySelectionDto(
  val libraryIds: List<String> = emptyList(),
)

data class DedupScanResultDto(
  val requestedLibraries: Int,
)

data class DedupClusterCoverMemberDto(
  val bookId: String,
  val title: String?,
  val thumbnailUrl: String,
)

data class DedupClusterSummaryDto(
  val id: String,
  val libraryId: String,
  val revision: Long,
  val title: String?,
  val memberCount: Int,
  val coverMembers: List<DedupClusterCoverMemberDto>,
  val hasSuggestion: Boolean,
  val lastModified: LocalDateTime,
  val lastAttemptError: String? = null,
)

data class DedupClusterMemberDto(
  val bookId: String,
  val seriesId: String?,
  val title: String?,
  val path: String?,
  val fileSize: Long?,
  val pageCount: Int?,
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

data class DedupPlanMemberDto(
  val bookId: String,
  val action: DedupResolutionAction,
)

data class DedupPlanDto(
  val keepCount: Int,
  val deleteCount: Int,
  val members: List<DedupPlanMemberDto>,
)

data class DedupClusterDetailDto(
  val summary: DedupClusterSummaryDto,
  val members: List<DedupClusterMemberDto>,
  val relations: List<DedupRelationDto>,
  val suggestion: DedupPlanDto?,
  val retryResolutionId: String? = null,
)

data class DedupPageEvidenceDto(
  val bookId: String,
  val pageNumber: Int,
  val matchedBookId: String?,
  val matchedPageNumber: Int?,
  val exactMatch: Boolean?,
  val thumbnailUrl: String,
)

data class DedupPageComparisonDto(
  val leftBookId: String,
  val rightBookId: String,
  val relationType: DedupRelationType,
  val pages: Map<String, List<DedupPageEvidenceDto>>,
)

data class DedupSuggestedResolutionRequestDto(
  @field:Min(1) val expectedRevision: Long,
)

data class DedupCustomResolutionRequestDto(
  @field:Min(1) val expectedRevision: Long,
  val deleteBookIds: List<String> = emptyList(),
)

data class DedupResolutionMemberDto(
  val bookId: String,
  val seriesId: String,
  val action: DedupResolutionAction,
  val title: String,
  val path: String,
  val expectedSize: Long?,
  val state: String,
  val resultCode: String?,
  val result: JsonNode?,
  val lastError: String?,
)

data class DedupResolutionDto(
  val id: String,
  val clusterId: String,
  val clusterRevision: Long,
  val mode: DedupResolutionMode,
  val state: DedupResolutionState,
  val actorId: String,
  val members: List<DedupResolutionMemberDto>,
  val result: JsonNode?,
  val created: LocalDateTime,
  val lastModified: LocalDateTime,
  val completed: LocalDateTime?,
)

data class DedupConflictDto(
  val code: String,
  val message: String,
  val resolutionId: String? = null,
  val partial: Boolean = false,
  val resolution: DedupResolutionDto? = null,
)
