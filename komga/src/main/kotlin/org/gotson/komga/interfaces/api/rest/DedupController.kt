package org.gotson.komga.interfaces.api.rest

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupEvidenceMaturity
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationStatus
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionMemberState
import org.gotson.komga.domain.model.DedupResolutionPlan
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.service.DedupClusterVerificationRequest
import org.gotson.komga.domain.service.DedupClusterVerificationStatus
import org.gotson.komga.domain.service.DedupCoverLifecycle
import org.gotson.komga.domain.service.DedupCustomMemberSelection
import org.gotson.komga.domain.service.DedupDeepVerificationLifecycle
import org.gotson.komga.domain.service.DedupResolutionExecutionException
import org.gotson.komga.domain.service.DedupResolutionLifecycle
import org.gotson.komga.domain.service.DedupResolutionValidationException
import org.gotson.komga.domain.service.DedupSuggestionPlanner
import org.gotson.komga.domain.service.DedupWorkLifecycle
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.rest.dto.DedupBulkVerificationRequestDto
import org.gotson.komga.interfaces.api.rest.dto.DedupBulkVerificationResultDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterCoverMemberDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterDetailDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterEligibilityBatchDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterEligibilityDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterEligibilityRequestDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterMemberDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterMemberProcessingDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterProcessingDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterSummaryDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterVerificationResultDto
import org.gotson.komga.interfaces.api.rest.dto.DedupConflictDto
import org.gotson.komga.interfaces.api.rest.dto.DedupCustomResolutionRequestDto
import org.gotson.komga.interfaces.api.rest.dto.DedupLibrarySelectionDto
import org.gotson.komga.interfaces.api.rest.dto.DedupLibrarySettingsDto
import org.gotson.komga.interfaces.api.rest.dto.DedupPageComparisonDto
import org.gotson.komga.interfaces.api.rest.dto.DedupPageEvidenceDto
import org.gotson.komga.interfaces.api.rest.dto.DedupPlanDto
import org.gotson.komga.interfaces.api.rest.dto.DedupPlanMemberDto
import org.gotson.komga.interfaces.api.rest.dto.DedupRelationDto
import org.gotson.komga.interfaces.api.rest.dto.DedupResolutionDto
import org.gotson.komga.interfaces.api.rest.dto.DedupResolutionMemberDto
import org.gotson.komga.interfaces.api.rest.dto.DedupResolutionRecoveryDto
import org.gotson.komga.interfaces.api.rest.dto.DedupResolutionSummaryDto
import org.gotson.komga.interfaces.api.rest.dto.DedupScanResultDto
import org.gotson.komga.interfaces.api.rest.dto.DedupSettingsDto
import org.gotson.komga.interfaces.api.rest.dto.DedupSettingsUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.DedupSingleVerificationRequestDto
import org.gotson.komga.interfaces.api.rest.dto.DedupStatusDto
import org.gotson.komga.interfaces.api.rest.dto.DedupSuggestedResolutionRequestDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

@RestController
@RequestMapping(value = ["api/v1/dedup"], produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "dedup")
class DedupController(
  private val dedupRepository: DedupRepository,
  private val resolutionRepository: DedupResolutionRepository,
  private val workLifecycle: DedupWorkLifecycle,
  private val resolutionLifecycle: DedupResolutionLifecycle,
  private val suggestionPlanner: DedupSuggestionPlanner,
  private val coverLifecycle: DedupCoverLifecycle,
  private val libraryRepository: LibraryRepository,
  private val bookRepository: BookRepository,
  private val objectMapper: ObjectMapper,
) {
  @GetMapping("settings")
  fun getSettings(): DedupSettingsDto = DedupSettingsDto(libraryRepository.findAll().sortedBy { it.name }.map { DedupLibrarySettingsDto(dedupRepository.findLibrarySettings(it.id) ?: DedupLibrarySettings(it.id)) })

  @PutMapping("settings")
  fun updateSettings(
    @Valid @RequestBody update: DedupSettingsUpdateDto,
  ): DedupSettingsDto {
    update.libraries.forEach { value ->
      if (libraryRepository.findByIdOrNull(value.libraryId) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found")
      if (value.scanInterval == Library.ScanInterval.DISABLED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use enabled=false instead of DISABLED")
      val current = dedupRepository.findLibrarySettings(value.libraryId)
      workLifecycle.saveSettings(
        DedupLibrarySettings(
          value.libraryId,
          value.enabled,
          value.paused,
          value.scanInterval,
          value.batchSize,
          value.maxDurationSeconds,
          value.quietPeriodSeconds,
          value.coverCandidateDistance,
          value.coverTopK,
          current?.createdDate ?: LocalDateTime.now(),
          LocalDateTime.now(),
        ),
      )
    }
    return getSettings()
  }

  @GetMapping("status")
  fun getStatus(): DedupStatusDto {
    val settings = dedupRepository.findAllLibrarySettings()
    val work = dedupRepository.countWorkByState()
    val clusters = dedupRepository.countClustersByStatus()
    val resolutions = resolutionRepository.countResolutionsByState()
    return DedupStatusDto(
      work = DedupWorkState.entries.associate { it.name to (work[it] ?: 0) },
      clusters = DedupClusterStatus.entries.associate { it.name to (clusters[it] ?: 0) },
      resolutions = DedupResolutionState.entries.associate { it.name to (resolutions[it] ?: 0) },
      gorseSync = dedupRepository.countGorseSyncStates(),
      enabledLibraries = settings.count { it.enabled },
      pausedLibraries = settings.count { it.enabled && it.paused },
    )
  }

  @PostMapping("scans")
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun requestScan(
    @RequestBody request: DedupLibrarySelectionDto,
  ): DedupScanResultDto {
    val configured = dedupRepository.findAllLibrarySettings().filter { it.enabled && !it.paused }
    val selected = if (request.libraryIds.isEmpty()) configured else configured.filter { it.libraryId in request.libraryIds }
    selected.forEach { workLifecycle.requestExactReconciliation(it.libraryId, bypassQuietPeriod = true) }
    return DedupScanResultDto(selected.size)
  }

  @PostMapping("scans/pause")
  fun pause(
    @RequestBody request: DedupLibrarySelectionDto,
  ): DedupSettingsDto = setPaused(request, true)

  @PostMapping("scans/resume")
  fun resume(
    @RequestBody request: DedupLibrarySelectionDto,
  ): DedupSettingsDto = setPaused(request, false)

  @PostMapping("work/{workId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun retryWork(
    @PathVariable workId: String,
  ) {
    if (!workLifecycle.retry(workId)) throw ResponseStatusException(HttpStatus.CONFLICT, "Work is not retryable")
  }

  @GetMapping("clusters")
  @Operation(summary = "List persistent duplicate clusters")
  fun getClusters(
    @RequestParam(name = "library_id", required = false) libraryId: String? = null,
    @RequestParam(name = "status", required = false) status: DedupClusterStatus? = null,
    @RequestParam(name = "evidence", required = false) evidence: DedupEvidenceMaturity? = null,
    page: Pageable,
  ): Page<DedupClusterSummaryDto> {
    validatePage(page)
    val values = dedupRepository.findClusters(libraryId, status, true, evidence, page.offset.toInt(), page.pageSize)
    val books = bookRepository.findAllByIds(values.flatMap { value -> value.members.filter { it.present }.map { it.bookId } }).associateBy { it.id }
    return PageImpl(
      values.map { it.toSummary(books) },
      page,
      dedupRepository.countClusters(libraryId, status, true, evidence),
    )
  }

  @PostMapping("clusters/eligibility")
  fun getClusterEligibility(
    @Valid @RequestBody request: DedupClusterEligibilityRequestDto,
  ): DedupClusterEligibilityBatchDto =
    DedupClusterEligibilityBatchDto(
      request.clusters.map { item ->
        val value =
          dedupRepository.findCluster(item.clusterId)
            ?: return@map DedupClusterEligibilityDto(item.clusterId, item.expectedRevision, "NOT_FOUND")
        if (value.cluster.revision != item.expectedRevision) return@map DedupClusterEligibilityDto(item.clusterId, item.expectedRevision, "STALE")
        runCatching { suggestionPlanner.evaluate(value) }
          .fold(
            onSuccess = { suggestion ->
              DedupClusterEligibilityDto(
                clusterId = item.clusterId,
                expectedRevision = item.expectedRevision,
                status = "READY",
                suggestionPlanAvailable = suggestion.eligibility.suggestionPlanAvailable,
                suggestedPlanEligible = suggestion.eligibility.suggestedPlanEligible,
                suggestedKeepCount = suggestion.plan?.keepCount ?: value.cluster.memberCount,
                suggestedDeleteCount = suggestion.plan?.deleteCount ?: 0,
                blockerCodes =
                  suggestion.eligibility.blockers
                    .map { it.code }
                    .distinct(),
              )
            },
            onFailure = { exception -> DedupClusterEligibilityDto(item.clusterId, item.expectedRevision, "FAILED", error = exception.message?.take(500)) },
          )
      },
    )

  @GetMapping("clusters/{clusterId}")
  fun getCluster(
    @PathVariable clusterId: String,
  ): DedupClusterDetailDto {
    val value = dedupRepository.findCluster(clusterId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val books = bookRepository.findAllByIds(value.members.filter { it.present }.map { it.bookId }).associateBy { it.id }
    return value.toDetail(books)
  }

  @GetMapping("clusters/{clusterId}/processing")
  fun getClusterProcessing(
    @PathVariable clusterId: String,
    @RequestParam(name = "expected_revision") expectedRevision: Long,
  ): DedupClusterProcessingDto {
    val value = dedupRepository.findCluster(clusterId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    if (value.cluster.revision != expectedRevision) throw DedupResolutionValidationException("CLUSTER_STALE", "Cluster revision changed")
    val suggestion = suggestionPlanner.evaluate(value)
    return DedupClusterProcessingDto(
      clusterId = clusterId,
      revision = value.cluster.revision,
      stateRevision = suggestion.eligibility.stateRevision,
      members =
        value.members.filter { it.present }.sortedBy { it.bookId }.map { member ->
          val state = suggestion.localStates[member.bookId]
          DedupClusterMemberProcessingDto(
            member.bookId,
            coverLifecycle.currentSourceIdentity(member.bookId)?.archiveHashState?.name ?: "MISSING",
            state?.reasonCodes.orEmpty(),
            state?.details.orEmpty(),
          )
        },
      suggestedPlan = suggestion.plan?.toDto(),
      eligibility = suggestion.eligibility,
      recovery = resolutionRecovery(value),
    )
  }

  @GetMapping("clusters/{clusterId}/pages")
  fun getPages(
    @PathVariable clusterId: String,
    @RequestParam(name = "left_book_id") leftBookId: String,
    @RequestParam(name = "right_book_id") rightBookId: String,
  ): DedupPageComparisonDto {
    if (leftBookId == rightBookId) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Two different Books are required")
    val value = dedupRepository.findCluster(clusterId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val ids =
      value.members
        .filter { it.present }
        .map { it.bookId }
        .toSet()
    if (leftBookId !in ids || rightBookId !in ids) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Both Books must be present cluster members")
    val relation =
      value.currentRelations().firstOrNull { setOf(it.bookLowId, it.bookHighId) == setOf(leftBookId, rightBookId) }
        ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Current relation is unavailable")
    val (low, high) = listOf(leftBookId, rightBookId).sorted()
    val evidence = runCatching { objectMapper.readTree(relation.evidenceJson) }.getOrNull()
    val matches = evidence?.path("matches")?.associate { (it.path("leftPage").asInt() to it.path("rightPage").asInt()) to it.path("exact").asBoolean() }.orEmpty()
    val pages =
      listOf(low, high).associateWith { bookId ->
        val identity = coverLifecycle.currentSourceIdentity(bookId)
        val features = identity?.let { dedupRepository.findPageFeatures(bookId, it.contentGeneration, DedupDeepVerificationLifecycle.PAGE_FEATURE_SCHEMA_VERSION) }.orEmpty()
        features.map { pageFeature ->
          val match =
            if (bookId == low)
              matches.entries.firstOrNull { it.key.first == pageFeature.pageNumber }?.let { it.key.second to it.value }
            else
              matches.entries.firstOrNull { it.key.second == pageFeature.pageNumber }?.let { it.key.first to it.value }
          DedupPageEvidenceDto(
            bookId,
            pageFeature.pageNumber,
            match?.let { if (bookId == low) high else low },
            match?.first,
            match?.second,
            "/api/v1/books/$bookId/pages/${pageFeature.pageNumber}/thumbnail",
          )
        }
      }
    return DedupPageComparisonDto(low, high, relation.type, pages)
  }

  @PostMapping("clusters/{clusterId}/verify")
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun verifyCluster(
    @PathVariable clusterId: String,
    @Valid @RequestBody request: DedupSingleVerificationRequestDto,
  ): DedupClusterVerificationResultDto = workLifecycle.requestClusterVerification(clusterId, request.expectedRevision).toDto()

  @PostMapping("clusters/verify")
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun verifyClusters(
    @Valid @RequestBody request: DedupBulkVerificationRequestDto,
  ): DedupBulkVerificationResultDto {
    val ids = request.clusters.map { it.clusterId }
    if (ids.distinct().size != ids.size) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate cluster IDs are not allowed")
    val results = workLifecycle.requestClusterVerifications(request.clusters.map { DedupClusterVerificationRequest(it.clusterId, it.expectedRevision) })
    return DedupBulkVerificationResultDto(
      results.size,
      results.count { it.status == DedupClusterVerificationStatus.QUEUED },
      results.count { it.status == DedupClusterVerificationStatus.STALE },
      results.count { it.status in setOf(DedupClusterVerificationStatus.NOT_FOUND, DedupClusterVerificationStatus.NO_ELIGIBLE_PAIR) },
      results.sumOf { it.queuedPairs },
      results.sumOf { it.skippedPairs },
      results.sumOf { it.failedPairs },
      results.map { it.toDto() },
    )
  }

  @PostMapping("clusters/{clusterId}/resolutions/suggested")
  @ResponseStatus(HttpStatus.CREATED)
  fun createSuggested(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable clusterId: String,
    @Valid @RequestBody request: DedupSuggestedResolutionRequestDto,
  ): DedupResolutionDto = resolutionLifecycle.createSuggested(clusterId, request.expectedRevision, request.stateRevision, request.planRevision, principal.user.id).toDto()

  @PostMapping("clusters/{clusterId}/resolutions/custom")
  @ResponseStatus(HttpStatus.CREATED)
  fun createCustom(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable clusterId: String,
    @Valid @RequestBody request: DedupCustomResolutionRequestDto,
  ): DedupResolutionDto {
    if (request.acknowledgedReasonCodes.distinct().size != request.acknowledgedReasonCodes.size) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate reason codes are not allowed")
    return resolutionLifecycle
      .createCustom(
        clusterId,
        request.expectedRevision,
        request.stateRevision,
        request.members.map { DedupCustomMemberSelection(it.bookId, it.action, it.keeperBookId) },
        request.acknowledgedReasonCodes.toSet(),
        principal.user.id,
      ).toDto()
  }

  @PostMapping("resolutions/{resolutionId}/retry")
  @ResponseStatus(HttpStatus.CREATED)
  fun retryResolution(
    @PathVariable resolutionId: String,
  ): DedupResolutionDto = resolutionLifecycle.retry(resolutionId).toDto()

  @PostMapping("resolutions/{resolutionId}/abandon")
  fun abandonResolution(
    @PathVariable resolutionId: String,
  ): DedupResolutionDto = resolutionLifecycle.abandon(resolutionId).toDto()

  @GetMapping("resolutions/{resolutionId}")
  fun getResolution(
    @PathVariable resolutionId: String,
  ): DedupResolutionDto = resolutionRepository.findResolution(resolutionId)?.toDto() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("resolutions")
  fun getResolutions(page: Pageable): Page<DedupResolutionDto> {
    validatePage(page)
    return PageImpl(resolutionRepository.findResolutions(page.offset.toInt(), page.pageSize).map { it.toDto() }, page, resolutionRepository.countResolutions())
  }

  @ExceptionHandler(DedupResolutionValidationException::class)
  fun handleValidation(exception: DedupResolutionValidationException): ResponseEntity<DedupConflictDto> = ResponseEntity.status(HttpStatus.CONFLICT).body(DedupConflictDto(exception.code, exception.message ?: exception.code, null, null, false, null))

  @ExceptionHandler(DedupResolutionExecutionException::class)
  fun handleExecution(exception: DedupResolutionExecutionException): ResponseEntity<DedupConflictDto> {
    val resolution = exception.resolutionId?.let(resolutionRepository::findResolution)
    val clusterState = resolution?.let { dedupRepository.findCluster(it.clusterId)?.cluster?.status }
    return ResponseEntity.status(HttpStatus.CONFLICT).body(
      DedupConflictDto(
        exception.code,
        exception.message ?: exception.code,
        exception.resolutionId,
        clusterState,
        exception.partial,
        resolution?.toDto(),
      ),
    )
  }

  private fun setPaused(
    request: DedupLibrarySelectionDto,
    paused: Boolean,
  ): DedupSettingsDto {
    val all = dedupRepository.findAllLibrarySettings()
    val selected = if (request.libraryIds.isEmpty()) all else all.filter { it.libraryId in request.libraryIds }
    selected.forEach { workLifecycle.saveSettings(it.copy(paused = paused, lastModifiedDate = LocalDateTime.now())) }
    return getSettings()
  }

  private fun DedupClusterWithMembers.toSummary(books: Map<String, org.gotson.komga.domain.model.Book>): DedupClusterSummaryDto {
    val present = members.filter { it.present }
    return DedupClusterSummaryDto(
      cluster.id,
      cluster.libraryId,
      cluster.revision,
      cluster.status,
      cluster.reviewable,
      cluster.memberCount,
      present.take(4).map { member -> DedupClusterCoverMemberDto(member.bookId, books[member.bookId]?.name, "/api/v1/books/${member.bookId}/thumbnail") },
      cluster.verifiedPairCount,
      cluster.totalPairCount,
      cluster.evidenceMaturity,
      cluster.reopenReason,
      cluster.lastModifiedDate,
      cluster.processedDate,
    )
  }

  private fun DedupClusterWithMembers.toDetail(booksById: Map<String, org.gotson.komga.domain.model.Book>): DedupClusterDetailDto {
    val present = members.filter { it.present }
    val books = present.mapNotNull { booksById[it.bookId] }
    val features = dedupRepository.findFeatures(present.map { it.bookId }.toSet()).associateBy { it.bookId }
    val activeBySeries = if (books.isEmpty()) emptyMap() else bookRepository.findAllBySeriesIds(books.map { it.seriesId }.toSet()).filter { it.deletedDate == null }.groupBy { it.seriesId }
    val memberDtos =
      present.sortedBy { it.bookId }.map { member ->
        val book = booksById[member.bookId]
        val activeCount = book?.let { activeBySeries[it.seriesId].orEmpty().size } ?: 0
        DedupClusterMemberDto(
          member.bookId,
          book?.seriesId,
          book?.name,
          book?.path?.toString(),
          book?.fileSize,
          features[member.bookId]?.pageCount,
          activeCount,
          book != null && book.deletedDate == null && activeCount == 1,
          "/api/v1/books/${member.bookId}/thumbnail",
        )
      }
    val relationDtos = currentRelations().map { it.toDto() }
    val last = cluster.lastResolutionId?.let(resolutionRepository::findResolution)?.let { DedupResolutionSummaryDto(it.id, it.mode, it.state, it.createdDate, it.completedDate) }
    return DedupClusterDetailDto(toSummary(booksById), memberDtos, relationDtos, last)
  }

  private fun resolutionRecovery(value: DedupClusterWithMembers): DedupResolutionRecoveryDto? {
    val resolution = value.cluster.lastResolutionId?.let(resolutionRepository::findResolution) ?: return null
    if (resolution.state !in setOf(DedupResolutionState.NEEDS_ATTENTION, DedupResolutionState.PARTIALLY_COMPLETED)) return null
    val members = resolutionRepository.findResolutionMembers(resolution.id)
    val irreversible =
      resolution.state == DedupResolutionState.PARTIALLY_COMPLETED ||
        members.any {
          it.state in setOf(DedupResolutionMemberState.DELETED, DedupResolutionMemberState.KOMGA_SAVED, DedupResolutionMemberState.GORSE_CONFIRMED, DedupResolutionMemberState.COMPLETED) ||
            (it.action == DedupResolutionAction.DELETE && Files.notExists(Path.of(it.expectedPath ?: it.pathSnapshot)))
        }
    return DedupResolutionRecoveryDto(resolution.id, if (irreversible) "RETRY" else "REAPPROVE")
  }

  private fun DedupClusterWithMembers.currentRelations(): List<DedupRelation> {
    val byId = members.filter { it.present }.associateBy { it.bookId }
    return dedupRepository.findRelationsForBooks(byId.keys).filter { value ->
      val low = byId[value.bookLowId] ?: return@filter false
      val high = byId[value.bookHighId] ?: return@filter false
      value.status !in setOf(DedupRelationStatus.STALE, DedupRelationStatus.REJECTED, DedupRelationStatus.FAILED_REVIEW) &&
        value.lowContentGeneration == low.sourceContentGeneration && value.highContentGeneration == high.sourceContentGeneration
    }
  }

  private fun DedupRelation.toDto() =
    DedupRelationDto(
      id,
      bookLowId,
      bookHighId,
      type,
      status,
      coverDistance,
      containedBookId,
      containerBookId,
      coverageLeft,
      coverageRight,
      orderConsistency,
      longestMatchedRun,
      unmatchedPrefixCount,
      unmatchedSuffixCount,
      unmatchedInternalCount,
      confidence,
      runCatching { objectMapper.readTree(evidenceJson) }.getOrNull(),
    )

  private fun DedupResolutionPlan.toDto() =
    DedupPlanDto(
      revision,
      keepCount,
      deleteCount,
      members.map { DedupPlanMemberDto(it.bookId, it.action, it.keeperBookId, it.directRelationId) },
    )

  private fun DedupResolution.toDto(): DedupResolutionDto =
    DedupResolutionDto(
      id,
      clusterId,
      clusterRevision,
      mode,
      planRevision,
      state,
      resolutionRepository.findResolutionMembers(id).map {
        DedupResolutionMemberDto(
          it.bookId,
          it.seriesId,
          it.action,
          it.keeperBookId,
          it.titleSnapshot,
          it.pathSnapshot,
          it.expectedSize,
          it.expectedArchiveHash,
          it.state,
          it.resultCode,
          it.resultJson?.let { json -> runCatching { objectMapper.readTree(json) }.getOrNull() },
          it.lastError,
        )
      },
      runCatching { objectMapper.readTree(resultJson) }.getOrNull(),
      createdDate,
      lastModifiedDate,
      completedDate,
    )

  private fun org.gotson.komga.domain.service.DedupClusterVerificationResult.toDto() = DedupClusterVerificationResultDto(clusterId, status.name, memberCount, pairCount, queuedPairs, skippedPairs, failedPairs)

  private fun validatePage(page: Pageable) {
    if (!page.isPaged || page.pageSize !in 1..100 || page.pageNumber < 0) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be between 1 and 100")
  }
}
