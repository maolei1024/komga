package org.gotson.komga.interfaces.api.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.service.DedupClusterLifecycle
import org.gotson.komga.domain.service.DedupCoverLifecycle
import org.gotson.komga.domain.service.DedupDeepVerificationLifecycle
import org.gotson.komga.domain.service.DedupResolutionExecutionException
import org.gotson.komga.domain.service.DedupResolutionLifecycle
import org.gotson.komga.domain.service.DedupResolutionValidationException
import org.gotson.komga.domain.service.DedupSuggestionPlanner
import org.gotson.komga.domain.service.DedupWorkLifecycle
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterCoverMemberDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterDetailDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterMemberDto
import org.gotson.komga.interfaces.api.rest.dto.DedupClusterSummaryDto
import org.gotson.komga.interfaces.api.rest.dto.DedupConflictDto
import org.gotson.komga.interfaces.api.rest.dto.DedupCustomResolutionRequestDto
import org.gotson.komga.interfaces.api.rest.dto.DedupLibraryRunStatusDto
import org.gotson.komga.interfaces.api.rest.dto.DedupLibrarySelectionDto
import org.gotson.komga.interfaces.api.rest.dto.DedupLibrarySettingsDto
import org.gotson.komga.interfaces.api.rest.dto.DedupPageComparisonDto
import org.gotson.komga.interfaces.api.rest.dto.DedupPageEvidenceDto
import org.gotson.komga.interfaces.api.rest.dto.DedupPlanDto
import org.gotson.komga.interfaces.api.rest.dto.DedupPlanMemberDto
import org.gotson.komga.interfaces.api.rest.dto.DedupRelationDto
import org.gotson.komga.interfaces.api.rest.dto.DedupResolutionDto
import org.gotson.komga.interfaces.api.rest.dto.DedupResolutionMemberDto
import org.gotson.komga.interfaces.api.rest.dto.DedupScanResultDto
import org.gotson.komga.interfaces.api.rest.dto.DedupSettingsDto
import org.gotson.komga.interfaces.api.rest.dto.DedupSettingsUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.DedupStatusDto
import org.gotson.komga.interfaces.api.rest.dto.DedupSuggestedResolutionRequestDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
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
import java.time.Duration
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
  private val clusterLifecycle: DedupClusterLifecycle,
  private val coverLifecycle: DedupCoverLifecycle,
  private val libraryRepository: LibraryRepository,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val objectMapper: ObjectMapper,
) {
  @GetMapping("settings")
  fun getSettings(): DedupSettingsDto =
    DedupSettingsDto(
      libraryRepository.findAll().sortedBy { it.name }.map { library ->
        (dedupRepository.findLibrarySettings(library.id) ?: DedupLibrarySettings(library.id)).toDto(library.name)
      },
    )

  @PutMapping("settings")
  fun updateSettings(
    @Valid @RequestBody update: DedupSettingsUpdateDto,
  ): DedupSettingsDto {
    if (update.libraries
        .map { it.libraryId }
        .distinct()
        .size != update.libraries.size
    ) {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate Library IDs are not allowed")
    }
    update.libraries.forEach { value ->
      if (libraryRepository.findByIdOrNull(value.libraryId) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found")
      if (value.scanInterval == Library.ScanInterval.DISABLED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use enabled=false instead of DISABLED")
      val current = dedupRepository.findLibrarySettings(value.libraryId)
      workLifecycle.saveSettings(
        DedupLibrarySettings(
          libraryId = value.libraryId,
          enabled = value.enabled,
          paused = value.paused,
          scanInterval = value.scanInterval,
          batchSize = value.batchSize,
          maxDurationSeconds = value.maxDurationSeconds,
          quietPeriodSeconds = value.quietPeriodSeconds,
          coverCandidateDistance = value.coverCandidateDistance,
          coverTopK = value.coverTopK,
          autoResolveSuggestions = value.autoResolveSuggestions ?: current?.autoResolveSuggestions ?: false,
          createdDate = current?.createdDate ?: LocalDateTime.now(),
          lastModifiedDate = LocalDateTime.now(),
          lastBatchDate = current?.lastBatchDate,
          lastBatchBookCount = current?.lastBatchBookCount ?: 0,
        ),
      )
    }
    return getSettings()
  }

  @GetMapping("status")
  fun getStatus(): DedupStatusDto {
    val libraries = libraryRepository.findAll().associateBy { it.id }
    val settings = dedupRepository.findAllLibrarySettings()
    val activeSettings = settings.filter { it.enabled && !it.paused }
    return DedupStatusDto(
      pendingScanBooks =
        dedupRepository.countPendingScanBooks(
          activeSettings.mapTo(mutableSetOf()) { it.libraryId },
          DedupCoverLifecycle.FEATURE_SCHEMA_VERSION,
        ),
      automaticVerificationPairs = dedupRepository.countPendingWork(DedupWorkType.VERIFY_RELATION),
      unresolvedClusters = dedupRepository.countUnresolvedClusters(),
      processedResolutions = resolutionRepository.countProcessedResolutions(),
      enabledLibraries = activeSettings.size,
      libraries =
        settings.filter { it.enabled }.map { value ->
          DedupLibraryRunStatusDto(
            libraryId = value.libraryId,
            libraryName = libraries[value.libraryId]?.name ?: value.libraryId,
            lastBatchDate = value.lastBatchDate,
            lastBatchBookCount = value.lastBatchBookCount,
            nextBatchDate = value.lastBatchDate?.plus(value.scanInterval.toDuration()),
          )
        },
    )
  }

  @PostMapping("scans")
  @ResponseStatus(HttpStatus.ACCEPTED)
  fun requestScan(
    @RequestBody request: DedupLibrarySelectionDto,
  ): DedupScanResultDto {
    val configured = dedupRepository.findAllLibrarySettings().filter { it.enabled && !it.paused }
    val selected = if (request.libraryIds.isEmpty()) configured else configured.filter { it.libraryId in request.libraryIds }
    selected.forEach { workLifecycle.requestLibraryBatch(it.libraryId) }
    return DedupScanResultDto(selected.size)
  }

  @GetMapping("clusters")
  @Operation(summary = "List current verified duplicate clusters")
  fun getClusters(
    @RequestParam(name = "library_id", required = false) libraryId: String? = null,
    page: Pageable,
  ): Page<DedupClusterSummaryDto> {
    validatePage(page)
    val values = dedupRepository.findUnresolvedClusters(libraryId, page.offset.toInt(), page.pageSize)
    val books = bookRepository.findAllByIds(values.flatMap { it.presentIds() }).associateBy { it.id }
    return PageImpl(
      values.map { value -> value.toSummary(books, suggestionPlanner.evaluate(value).plan != null, lastAttemptError(value)) },
      page,
      dedupRepository.countUnresolvedClusters(libraryId),
    )
  }

  @GetMapping("clusters/{clusterId}")
  fun getCluster(
    @PathVariable clusterId: String,
  ): DedupClusterDetailDto {
    val value = dedupRepository.findCluster(clusterId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    if (!value.cluster.reviewable || value.cluster.status !in setOf(DedupClusterStatus.UNPROCESSED, DedupClusterStatus.NEEDS_ATTENTION)) {
      throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
    val ids = value.presentIds()
    val books = bookRepository.findAllByIds(ids).associateBy { it.id }
    val suggestion = suggestionPlanner.evaluate(value).plan
    val lastResolution = value.cluster.lastResolutionId?.let(resolutionRepository::findResolution)
    return DedupClusterDetailDto(
      summary = value.toSummary(books, suggestion != null, lastAttemptError(value)),
      members = ids.sorted().map { id -> books[id].toMemberDto() },
      relations = clusterLifecycle.currentReviewRelations(ids).map(::relationDto),
      suggestion = suggestion?.let { plan -> DedupPlanDto(plan.keepCount, plan.deleteCount, plan.members.map { DedupPlanMemberDto(it.bookId, it.action) }) },
      retryResolutionId = lastResolution?.id?.takeIf { lastResolution.state == DedupResolutionState.PARTIALLY_COMPLETED },
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
    val ids = value.presentIds()
    if (leftBookId !in ids || rightBookId !in ids) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Both Books must be current cluster members")
    val relation =
      clusterLifecycle.currentReviewRelations(ids).firstOrNull { setOf(it.bookLowId, it.bookHighId) == setOf(leftBookId, rightBookId) }
        ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Current verified relation is unavailable")
    val (low, high) = listOf(leftBookId, rightBookId).sorted()
    val evidence = parseJson(relation.evidenceJson)
    val matches = evidence?.path("matches")?.associate { (it.path("leftPage").asInt() to it.path("rightPage").asInt()) to it.path("exact").asBoolean() }.orEmpty()
    val pages =
      listOf(low, high).associateWith { bookId ->
        val identity = coverLifecycle.currentSourceIdentity(bookId)
        val stored = identity?.let { dedupRepository.findPageFeatures(bookId, it.contentGeneration, DedupDeepVerificationLifecycle.PAGE_FEATURE_SCHEMA_VERSION) }.orEmpty()
        val pageNumbers = if (stored.isNotEmpty()) stored.map { it.pageNumber } else (1..(mediaRepository.findByIdOrNull(bookId)?.pageCount ?: 0)).toList()
        pageNumbers.map { pageNumber ->
          val match =
            if (bookId == low)
              matches.entries.firstOrNull { it.key.first == pageNumber }?.let { it.key.second to it.value }
            else
              matches.entries.firstOrNull { it.key.second == pageNumber }?.let { it.key.first to it.value }
          DedupPageEvidenceDto(
            bookId = bookId,
            pageNumber = pageNumber,
            matchedBookId = match?.let { if (bookId == low) high else low },
            matchedPageNumber = match?.first,
            exactMatch = match?.second,
            thumbnailUrl = "/api/v1/books/$bookId/pages/$pageNumber/thumbnail",
          )
        }
      }
    return DedupPageComparisonDto(low, high, relation.type, pages)
  }

  @PostMapping("clusters/{clusterId}/resolutions/suggested")
  @ResponseStatus(HttpStatus.CREATED)
  fun createSuggested(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable clusterId: String,
    @Valid @RequestBody request: DedupSuggestedResolutionRequestDto,
  ): DedupResolutionDto = resolutionLifecycle.createSuggested(clusterId, request.expectedRevision, principal.user.id).toDto()

  @PostMapping("clusters/{clusterId}/resolutions/custom")
  @ResponseStatus(HttpStatus.CREATED)
  fun createCustom(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable clusterId: String,
    @Valid @RequestBody request: DedupCustomResolutionRequestDto,
  ): DedupResolutionDto {
    if (request.deleteBookIds.any(String::isBlank)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Book IDs cannot be blank")
    return resolutionLifecycle.createCustom(clusterId, request.expectedRevision, request.deleteBookIds, principal.user.id).toDto()
  }

  @PostMapping("resolutions/{resolutionId}/retry")
  @ResponseStatus(HttpStatus.CREATED)
  fun retryResolution(
    @PathVariable resolutionId: String,
  ): DedupResolutionDto = resolutionLifecycle.retry(resolutionId).toDto()

  @GetMapping("resolutions")
  fun getResolutions(page: Pageable): Page<DedupResolutionDto> {
    validatePage(page)
    return PageImpl(
      resolutionRepository.findProcessedResolutions(page.offset.toInt(), page.pageSize).map { it.toDto() },
      page,
      resolutionRepository.countProcessedResolutions(),
    )
  }

  @GetMapping("resolutions/{resolutionId}")
  fun getResolution(
    @PathVariable resolutionId: String,
  ): DedupResolutionDto = resolutionRepository.findResolution(resolutionId)?.toDto() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @ExceptionHandler(DedupResolutionValidationException::class)
  fun handleValidation(exception: DedupResolutionValidationException): ResponseEntity<DedupConflictDto> = ResponseEntity.status(HttpStatus.CONFLICT).body(DedupConflictDto(exception.code, exception.message ?: exception.code))

  @ExceptionHandler(DedupResolutionExecutionException::class)
  fun handleExecution(exception: DedupResolutionExecutionException): ResponseEntity<DedupConflictDto> {
    val resolution = exception.resolutionId?.let(resolutionRepository::findResolution)
    return ResponseEntity.status(HttpStatus.CONFLICT).body(
      DedupConflictDto(
        code = exception.code,
        message = exception.message ?: exception.code,
        resolutionId = exception.resolutionId,
        partial = exception.partial,
        resolution = resolution?.toDto(),
      ),
    )
  }

  private fun DedupLibrarySettings.toDto(libraryName: String) =
    DedupLibrarySettingsDto(
      libraryId,
      libraryName,
      enabled,
      paused,
      scanInterval,
      batchSize,
      maxDurationSeconds,
      quietPeriodSeconds,
      coverCandidateDistance,
      coverTopK,
      autoResolveSuggestions,
      lastBatchDate,
      lastBatchBookCount,
    )

  private fun DedupClusterWithMembers.toSummary(
    books: Map<String, Book>,
    hasSuggestion: Boolean,
    lastError: String?,
  ): DedupClusterSummaryDto {
    val active = presentIds().sorted().mapNotNull(books::get)
    return DedupClusterSummaryDto(
      id = cluster.id,
      libraryId = cluster.libraryId,
      revision = cluster.revision,
      title = active.firstOrNull()?.name,
      memberCount = active.size,
      coverMembers = active.take(4).map { DedupClusterCoverMemberDto(it.id, it.name, "/api/v1/books/${it.id}/thumbnail") },
      hasSuggestion = hasSuggestion,
      lastModified = cluster.lastModifiedDate,
      lastAttemptError = lastError,
    )
  }

  private fun Book?.toMemberDto(): DedupClusterMemberDto {
    val book = this ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Cluster member Book is unavailable")
    return DedupClusterMemberDto(
      bookId = book.id,
      seriesId = book.seriesId,
      title = book.name,
      path = book.path.toString(),
      fileSize = book.fileSize,
      pageCount = coverLifecycle.currentSourceIdentity(book.id)?.pageCount,
      thumbnailUrl = "/api/v1/books/${book.id}/thumbnail",
    )
  }

  private fun relationDto(value: DedupRelation) =
    DedupRelationDto(
      id = value.id,
      leftBookId = value.bookLowId,
      rightBookId = value.bookHighId,
      type = value.type,
      status = value.status,
      coverDistance = value.coverDistance,
      containedBookId = value.containedBookId,
      containerBookId = value.containerBookId,
      coverageLeft = value.coverageLeft,
      coverageRight = value.coverageRight,
      orderConsistency = value.orderConsistency,
      longestMatchedRun = value.longestMatchedRun,
      unmatchedPrefixCount = value.unmatchedPrefixCount,
      unmatchedSuffixCount = value.unmatchedSuffixCount,
      unmatchedInternalCount = value.unmatchedInternalCount,
      confidence = value.confidence,
      evidence = parseJson(value.evidenceJson),
    )

  private fun DedupResolution.toDto(): DedupResolutionDto =
    DedupResolutionDto(
      id = id,
      clusterId = clusterId,
      clusterRevision = clusterRevision,
      mode = mode,
      state = state,
      actorId = actorId,
      members =
        resolutionRepository.findResolutionMembers(id).map { member ->
          DedupResolutionMemberDto(
            bookId = member.bookId,
            seriesId = member.seriesId,
            action = member.action,
            title = member.titleSnapshot,
            path = member.pathSnapshot,
            expectedSize = member.expectedSize,
            state = member.state.name,
            resultCode = member.resultCode,
            result = member.resultJson?.let(::parseJson),
            lastError = member.lastError,
          )
        },
      result = parseJson(resultJson),
      created = createdDate,
      lastModified = lastModifiedDate,
      completed = completedDate,
    )

  private fun lastAttemptError(value: DedupClusterWithMembers): String? {
    val resolution = value.cluster.lastResolutionId?.let(resolutionRepository::findResolution) ?: return null
    if (resolution.state == DedupResolutionState.PROCESSED) return null
    val result = parseJson(resolution.resultJson)
    return result?.path("message")?.asText()?.takeIf { it.isNotBlank() } ?: result?.path("code")?.asText()?.takeIf { it.isNotBlank() }
  }

  private fun parseJson(value: String): JsonNode? = runCatching { objectMapper.readTree(value) }.getOrNull()

  private fun DedupClusterWithMembers.presentIds(): Set<String> = members.filter { it.present }.map { it.bookId }.toSet()

  private fun validatePage(page: Pageable) {
    if (page.isUnpaged || page.pageSize !in 1..100 || page.offset > Int.MAX_VALUE) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A page size between 1 and 100 is required")
  }

  private fun Library.ScanInterval.toDuration(): Duration =
    when (this) {
      Library.ScanInterval.DISABLED -> Duration.ZERO
      Library.ScanInterval.HOURLY -> Duration.ofHours(1)
      Library.ScanInterval.EVERY_6H -> Duration.ofHours(6)
      Library.ScanInterval.EVERY_12H -> Duration.ofHours(12)
      Library.ScanInterval.DAILY -> Duration.ofDays(1)
      Library.ScanInterval.WEEKLY -> Duration.ofDays(7)
    }
}
