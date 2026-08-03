package org.gotson.komga.interfaces.api.rest

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupOverride
import org.gotson.komga.domain.model.DedupOverrideType
import org.gotson.komga.domain.model.DedupReviewCase
import org.gotson.komga.domain.model.DedupReviewCaseOrigin
import org.gotson.komga.domain.model.DedupReviewCaseStatus
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupDecisionRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.service.DedupCoverLifecycle
import org.gotson.komga.domain.service.DedupDecisionLifecycle
import org.gotson.komga.domain.service.DedupDecisionValidationException
import org.gotson.komga.domain.service.DedupEligibilityPolicy
import org.gotson.komga.domain.service.DedupWorkLifecycle
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.persistence.BookDtoRepository
import org.gotson.komga.interfaces.api.rest.dto.DedupCustomDecisionRequestDto
import org.gotson.komga.interfaces.api.rest.dto.DedupDecisionDto
import org.gotson.komga.interfaces.api.rest.dto.DedupDecisionItemDto
import org.gotson.komga.interfaces.api.rest.dto.DedupKeeperUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.DedupLibrarySelectionDto
import org.gotson.komga.interfaces.api.rest.dto.DedupLibrarySettingsDto
import org.gotson.komga.interfaces.api.rest.dto.DedupOverrideRequestDto
import org.gotson.komga.interfaces.api.rest.dto.DedupPageComparisonDto
import org.gotson.komga.interfaces.api.rest.dto.DedupPageEvidenceDto
import org.gotson.komga.interfaces.api.rest.dto.DedupReviewCaseDto
import org.gotson.komga.interfaces.api.rest.dto.DedupReviewCaseMemberDto
import org.gotson.komga.interfaces.api.rest.dto.DedupScanResultDto
import org.gotson.komga.interfaces.api.rest.dto.DedupSettingsDto
import org.gotson.komga.interfaces.api.rest.dto.DedupSettingsUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.DedupStatusDto
import org.gotson.komga.interfaces.api.rest.dto.DedupSuggestedDecisionRequestDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
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
import java.time.LocalDateTime

@RestController
@RequestMapping(value = ["api/v1/dedup"], produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "dedup")
class DedupController(
  private val dedupRepository: DedupRepository,
  private val dedupWorkLifecycle: DedupWorkLifecycle,
  private val libraryRepository: LibraryRepository,
  private val bookRepository: BookRepository,
  private val bookDtoRepository: BookDtoRepository,
  private val eligibilityPolicy: DedupEligibilityPolicy,
  private val decisionLifecycle: DedupDecisionLifecycle,
  private val decisionRepository: DedupDecisionRepository,
  private val objectMapper: ObjectMapper,
  private val coverLifecycle: DedupCoverLifecycle,
) {
  @GetMapping("settings")
  @Operation(summary = "Retrieve duplicate-content management settings")
  fun getSettings(): DedupSettingsDto =
    DedupSettingsDto(
      libraryRepository
        .findAll()
        .sortedBy { it.name }
        .map { library -> DedupLibrarySettingsDto(dedupRepository.findLibrarySettings(library.id) ?: DedupLibrarySettings(library.id)) },
    )

  @PutMapping("settings")
  @Operation(summary = "Update duplicate-content management settings")
  fun updateSettings(
    @Valid @RequestBody update: DedupSettingsUpdateDto,
  ): DedupSettingsDto {
    update.libraries.forEach { value ->
      if (libraryRepository.findByIdOrNull(value.libraryId) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Library not found")
      if (value.scanInterval == Library.ScanInterval.DISABLED) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use enabled=false instead of DISABLED")
      val existing = dedupRepository.findLibrarySettings(value.libraryId)
      dedupWorkLifecycle.saveSettings(
        DedupLibrarySettings(
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
          createdDate = existing?.createdDate ?: LocalDateTime.now(),
          lastModifiedDate = LocalDateTime.now(),
        ),
      )
    }
    return getSettings()
  }

  @GetMapping("status")
  @Operation(summary = "Retrieve duplicate-content work status")
  fun getStatus(): DedupStatusDto {
    val settings = dedupRepository.findAllLibrarySettings()
    val cases = dedupRepository.findReviewCases()
    val workCounts = dedupRepository.countWorkByState()
    val decisionCounts = decisionRepository.countDecisionStates()
    val itemCounts = decisionRepository.countDecisionItemStates()
    return DedupStatusDto(
      work = DedupWorkState.entries.associate { it.name to (workCounts[it] ?: 0) },
      decisions =
        org.gotson.komga.domain.model.DedupDecisionState.entries
          .associate { it.name to (decisionCounts[it] ?: 0) },
      decisionItems =
        org.gotson.komga.domain.model.DedupDecisionItemState.entries
          .associate { it.name to (itemCounts[it] ?: 0) },
      gorseSync = decisionRepository.countGorseSyncStates(),
      enabledLibraries = settings.count { it.enabled },
      pausedLibraries = settings.count { it.enabled && it.paused },
      reviewCases = cases.size,
      exactFileCases = cases.count { it.origin == DedupReviewCaseOrigin.EXACT_FILE },
    )
  }

  @PostMapping("scans")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Request an incremental duplicate-content scan")
  fun requestScan(
    @RequestBody request: DedupLibrarySelectionDto,
  ): DedupScanResultDto {
    val configured = dedupRepository.findAllLibrarySettings().filter { it.enabled && !it.paused }
    val selected = if (request.libraryIds.isEmpty()) configured else configured.filter { it.libraryId in request.libraryIds }
    selected.forEach { dedupWorkLifecycle.requestExactReconciliation(it.libraryId, bypassQuietPeriod = true) }
    return DedupScanResultDto(selected.size)
  }

  @PostMapping("scans/pause")
  @Operation(summary = "Pause duplicate-content scans")
  fun pause(
    @RequestBody request: DedupLibrarySelectionDto,
  ): DedupSettingsDto = setPaused(request, true)

  @PostMapping("scans/resume")
  @Operation(summary = "Resume duplicate-content scans")
  fun resume(
    @RequestBody request: DedupLibrarySelectionDto,
  ): DedupSettingsDto = setPaused(request, false)

  @PostMapping("work/{workId}/retry")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Retry failed duplicate-content work")
  fun retryWork(
    @PathVariable workId: String,
  ) {
    if (!dedupWorkLifecycle.retry(workId)) throw ResponseStatusException(HttpStatus.CONFLICT, "Work is not retryable")
  }

  @GetMapping("cases")
  @Operation(summary = "List duplicate-content review cases")
  fun getCases(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @RequestParam(name = "library_id", required = false) libraryId: String? = null,
    @RequestParam(name = "origin", required = false) origin: DedupReviewCaseOrigin? = null,
    page: Pageable,
  ): Page<DedupReviewCaseDto> {
    val all = dedupRepository.findReviewCases(libraryId, origin)
    val from = if (page.isPaged) page.offset.toInt().coerceAtMost(all.size) else 0
    val to = if (page.isPaged) (from + page.pageSize).coerceAtMost(all.size) else all.size
    val content = all.subList(from, to).map { it.toDto(principal.user.id) }
    val responsePage = if (page.isPaged) page else PageRequest.of(0, maxOf(all.size, 20))
    return PageImpl(content, responsePage, all.size.toLong())
  }

  @GetMapping("cases/{caseId}")
  @Operation(summary = "Retrieve a duplicate-content review case")
  fun getCase(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable caseId: String,
  ): DedupReviewCaseDto =
    dedupRepository.findReviewCase(caseId)?.toDto(principal.user.id)
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("cases/{caseId}/pages")
  @Operation(summary = "Retrieve current-generation page evidence for a pairwise review case")
  fun getPageComparison(
    @PathVariable caseId: String,
  ): DedupPageComparisonDto {
    val reviewCase = dedupRepository.findReviewCase(caseId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val ids = reviewCase.memberBookIds.sorted()
    if (ids.size != 2) throw ResponseStatusException(HttpStatus.CONFLICT, "Page comparison requires a pairwise case")
    val relation = dedupRepository.findRelation(ids[0], ids[1]) ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Current relation is unavailable")
    val evidence = runCatching { objectMapper.readTree(relation.evidenceJson) }.getOrNull()
    val matches =
      evidence
        ?.path("matches")
        ?.associate { node ->
          (node.path("leftPage").asInt() to node.path("rightPage").asInt()) to node.path("exact").asBoolean()
        }.orEmpty()
    val reverse = matches.entries.associate { (pages, exact) -> (pages.second to pages.first) to exact }
    val pages =
      ids.associateWith { bookId ->
        val identity = coverLifecycle.currentSourceIdentity(bookId)
        val features =
          identity
            ?.let {
              dedupRepository.findPageFeatures(bookId, it.contentGeneration, org.gotson.komga.domain.service.DedupDeepVerificationLifecycle.PAGE_FEATURE_SCHEMA_VERSION)
            }.orEmpty()
        features.map { page ->
          val pair =
            if (bookId == ids[0]) {
              matches.entries.firstOrNull { it.key.first == page.pageNumber }?.let { it.key.second to it.value }
            } else {
              reverse.entries.firstOrNull { it.key.first == page.pageNumber }?.let { it.key.second to it.value }
            }
          DedupPageEvidenceDto(
            bookId = bookId,
            pageNumber = page.pageNumber,
            matchedBookId = pair?.let { if (bookId == ids[0]) ids[1] else ids[0] },
            matchedPageNumber = pair?.first,
            exactMatch = pair?.second,
            thumbnailUrl = "/api/v1/books/$bookId/pages/${page.pageNumber}/thumbnail",
          )
        }
      }
    return DedupPageComparisonDto(relation.type.name, pages)
  }

  @PutMapping("cases/{caseId}/keeper")
  @Operation(summary = "Select the keeper for a duplicate-content review case")
  fun setKeeper(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable caseId: String,
    @Valid @RequestBody update: DedupKeeperUpdateDto,
  ): DedupReviewCaseDto {
    val current = dedupRepository.findReviewCase(caseId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    if (update.bookId !in current.memberBookIds) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Keeper must be a case member")
    if (!dedupRepository.setReviewCaseKeeper(caseId, update.expectedRevision, update.bookId)) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "Review case changed")
    }
    return dedupRepository.findReviewCase(caseId)!!.toDto(principal.user.id)
  }

  @PostMapping("cases/{caseId}/overrides")
  @Operation(summary = "Record a version-bound duplicate-content override")
  fun addOverride(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable caseId: String,
    @Valid @RequestBody request: DedupOverrideRequestDto,
  ): DedupReviewCaseDto {
    val current = dedupRepository.findReviewCase(caseId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
    val type =
      try {
        DedupOverrideType.valueOf(request.type)
      } catch (_: IllegalArgumentException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported override type")
      }
    val memberIds = current.memberBookIds.sorted()
    val relation = memberIds.takeIf { it.size == 2 }?.let { dedupRepository.findRelation(it[0], it[1]) }
    if (type != DedupOverrideType.PROTECTED && relation?.type?.name == "EXACT_FILE") {
      throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Exact-file identity cannot be dismissed as an edition override")
    }
    val override =
      if (type == DedupOverrideType.PROTECTED) {
        val bookId = request.bookId ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Protected override requires a book")
        if (bookId !in current.memberBookIds) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Protected book must be a case member")
        DedupOverride(
          id = TsidCreator.getTsid256().toString(),
          type = type,
          bookId = bookId,
          actorId = principal.user.id,
          reason = request.reason,
        )
      } else {
        if (memberIds.size != 2 || relation == null) throw ResponseStatusException(HttpStatus.CONFLICT, "A current direct relation is required")
        DedupOverride(
          id = TsidCreator.getTsid256().toString(),
          type = type,
          bookLowId = memberIds[0],
          bookHighId = memberIds[1],
          lowContentGeneration = relation.lowContentGeneration,
          highContentGeneration = relation.highContentGeneration,
          lowCoverGeneration = relation.lowCoverGeneration,
          highCoverGeneration = relation.highCoverGeneration,
          actorId = principal.user.id,
          reason = request.reason,
        )
      }
    val newStatus = if (type == DedupOverrideType.PROTECTED) current.status else DedupReviewCaseStatus.IGNORED
    if (!dedupRepository.applyOverride(caseId, request.expectedRevision, override, newStatus)) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "Review case changed")
    }
    return dedupRepository.findReviewCase(caseId)!!.toDto(principal.user.id)
  }

  @PostMapping("cases/{caseId}/verify")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Reanalyze a duplicate-content review case")
  fun reanalyzeCase(
    @PathVariable caseId: String,
  ) {
    if (dedupWorkLifecycle.requestCaseVerification(caseId) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND)
  }

  @PostMapping("cases/{caseId}/decisions/suggest")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create an immutable approved suggested dedup decision")
  fun createSuggestedDecision(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable caseId: String,
    @Valid @RequestBody request: DedupSuggestedDecisionRequestDto,
  ): DedupDecisionDto =
    decisionCall {
      decisionLifecycle
        .createSuggested(caseId, request.expectedRevision, request.stateRevision, principal.user.id)
        .toDto()
    }

  @PostMapping("cases/{caseId}/decisions/custom")
  @ResponseStatus(HttpStatus.CREATED)
  @Operation(summary = "Create an immutable approved custom dedup decision")
  fun createCustomDecision(
    @AuthenticationPrincipal principal: KomgaPrincipal,
    @PathVariable caseId: String,
    @Valid @RequestBody request: DedupCustomDecisionRequestDto,
  ): DedupDecisionDto =
    decisionCall {
      decisionLifecycle
        .createManual(
          caseId,
          request.expectedRevision,
          request.keeperBookId,
          request.removeBookIds,
          request.stateRevision,
          request.acknowledgedReasonCodes,
          principal.user.id,
        ).toDto()
    }

  @PostMapping("decisions/{decisionId}/execute")
  @ResponseStatus(HttpStatus.ACCEPTED)
  @Operation(summary = "Execute an approved dedup decision through the dedicated Book deletion saga")
  fun executeDecision(
    @PathVariable decisionId: String,
  ): DedupDecisionDto = decisionCall { decisionLifecycle.requestExecution(decisionId).toDto() }

  @GetMapping("decisions/{decisionId}")
  @Operation(summary = "Retrieve a dedup decision and its per-Book saga results")
  fun getDecision(
    @PathVariable decisionId: String,
  ): DedupDecisionDto =
    decisionRepository.findDecision(decisionId)?.toDto()
      ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

  @GetMapping("decisions")
  @Operation(summary = "List immutable dedup decision audit records")
  fun getDecisions(page: Pageable): Page<DedupDecisionDto> {
    val all = decisionRepository.findAllDecisions()
    val from = if (page.isPaged) page.offset.toInt().coerceAtMost(all.size) else 0
    val to = if (page.isPaged) (from + page.pageSize).coerceAtMost(all.size) else all.size
    val responsePage = if (page.isPaged) page else PageRequest.of(0, maxOf(all.size, 20))
    return PageImpl(all.subList(from, to).map { it.toDto() }, responsePage, all.size.toLong())
  }

  private fun setPaused(
    request: DedupLibrarySelectionDto,
    paused: Boolean,
  ): DedupSettingsDto {
    val settings = dedupRepository.findAllLibrarySettings()
    val selected = if (request.libraryIds.isEmpty()) settings else settings.filter { it.libraryId in request.libraryIds }
    selected.forEach { dedupWorkLifecycle.saveSettings(it.copy(paused = paused, lastModifiedDate = LocalDateTime.now())) }
    return getSettings()
  }

  private fun DedupReviewCase.toDto(userId: String): DedupReviewCaseDto {
    val books = memberBookIds.mapNotNull(bookRepository::findByIdOrNull)
    val activeBySeries =
      if (books.isEmpty()) emptyMap() else bookRepository.findAllBySeriesIds(books.map { it.seriesId }.toSet()).filter { it.deletedDate == null }.groupBy { it.seriesId }
    val members =
      memberBookIds
        .sorted()
        .map { bookId ->
          val book = books.firstOrNull { it.id == bookId }
          val activeCount = book?.let { activeBySeries[it.seriesId].orEmpty().size } ?: 0
          DedupReviewCaseMemberDto(
            book = bookDtoRepository.findByIdOrNull(bookId, userId),
            bookId = bookId,
            activeBookCountInSeries = activeCount,
            inMvpScope = book != null && book.deletedDate == null && activeCount == 1,
          )
        }
    val directRelation = memberBookIds.takeIf { it.size == 2 }?.toList()?.let { dedupRepository.findRelation(it[0], it[1]) }
    val eligibility = eligibilityPolicy.evaluate(this)

    return DedupReviewCaseDto(
      id = id,
      libraryId = libraryId,
      revision = revision,
      status = status,
      origin = origin,
      relationType = directRelation?.type?.name ?: if (origin == DedupReviewCaseOrigin.EXACT_FILE) "EXACT_FILE" else "VISUALLY_SIMILAR",
      coverDistance = directRelation?.coverDistance,
      coverageLeft = directRelation?.coverageLeft,
      coverageRight = directRelation?.coverageRight,
      longestMatchedRun = directRelation?.longestMatchedRun,
      unmatchedPrefixCount = directRelation?.unmatchedPrefixCount,
      unmatchedSuffixCount = directRelation?.unmatchedSuffixCount,
      unmatchedInternalCount = directRelation?.unmatchedInternalCount,
      suggestedKeeperBookId = suggestedKeeperBookId,
      members = members,
      eligibility = eligibility,
      created = createdDate,
      lastModified = lastModifiedDate,
    )
  }

  private fun org.gotson.komga.domain.model.DedupDecision.toDto(): DedupDecisionDto =
    decisionRepository.findDecisionItems(id).let { decisionItems ->
      val syncStates = decisionItems.mapNotNull { decisionRepository.findGorseSync(it.seriesId)?.state }
      DedupDecisionDto(
        id = id,
        reviewCaseId = reviewCaseId,
        planRevision = planRevision,
        mode = mode,
        keeperBookId = keeperBookId,
        state = state,
        items =
          decisionItems.map { item ->
            DedupDecisionItemDto(
              id = item.id,
              bookId = item.bookId,
              seriesId = item.seriesId,
              title = item.titleSnapshot,
              path = item.pathSnapshot,
              expectedSize = item.expectedSize,
              expectedArchiveHash = item.expectedArchiveHash,
              state = item.state,
              attemptCount = item.attemptCount,
              resultCode = item.resultCode,
              result = item.resultJson?.let { runCatching { objectMapper.readTree(it) }.getOrNull() },
              stabilityNotBefore = item.stabilityNotBefore,
            )
          },
        result = runCatching { objectMapper.readTree(resultJson) }.getOrNull(),
        gorseSyncState =
          when {
            syncStates.isNotEmpty() && syncStates.all { it == "SUCCEEDED" } -> "SUCCEEDED"
            syncStates.any { it == "FAILED_REVIEW" } -> "FAILED_REVIEW"
            else -> gorseSyncState
          },
        remoteConfirmationState = remoteConfirmationState,
        approved = approvedDate,
        executed = executedDate,
        completed = completedDate,
      )
    }

  private fun <T> decisionCall(block: () -> T): T =
    try {
      block()
    } catch (exception: DedupDecisionValidationException) {
      throw ResponseStatusException(HttpStatus.CONFLICT, "${exception.code}: ${exception.message}")
    }
}
