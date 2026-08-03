package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import org.gotson.komga.application.tasks.HIGH_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.DedupDecision
import org.gotson.komga.domain.model.DedupDecisionItem
import org.gotson.komga.domain.model.DedupDecisionItemState
import org.gotson.komga.domain.model.DedupDecisionMode
import org.gotson.komga.domain.model.DedupDecisionState
import org.gotson.komga.domain.model.DedupDeletionResultCode
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupReviewCase
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupDecisionRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

data class DedupKeeperSnapshot(
  val bookId: String,
  val seriesId: String,
  val libraryId: String,
  val contentGeneration: String,
  val seriesScopeRevision: String,
  val file: DedupStrongFileIdentity,
)

class DedupDecisionValidationException(
  val code: String,
  message: String,
) : IllegalStateException(message)

@Service
class DedupDecisionLifecycle(
  private val dedupRepository: DedupRepository,
  private val decisionRepository: DedupDecisionRepository,
  private val bookRepository: BookRepository,
  private val coverLifecycle: DedupCoverLifecycle,
  private val eligibilityPolicy: DedupEligibilityPolicy,
  private val localStateLifecycle: DedupLocalStateLifecycle,
  private val physicalDeletionLifecycle: DedupPhysicalBookDeletionLifecycle,
  private val taskEmitter: TaskEmitter,
  private val objectMapper: ObjectMapper,
) {
  private val decisionLease = Duration.ofMinutes(30)

  fun createSuggested(
    caseId: String,
    expectedRevision: Long,
    stateRevision: String,
    actorId: String,
  ): DedupDecision {
    val reviewCase = currentCase(caseId, expectedRevision)
    val keeper = reviewCase.suggestedKeeperBookId ?: conflict("NO_KEEPER", "Select a keeper before creating a suggested decision")
    val losers = reviewCase.memberBookIds - keeper
    val eligibility = eligibilityPolicy.evaluate(reviewCase, keeper, losers)
    if (eligibility.stateRevision != stateRevision) conflict("STATE_CHANGED", "Local state changed after eligibility was displayed")
    if (!eligibility.suggestedPlanEligible) conflict("SUGGESTED_NOT_ELIGIBLE", "Suggested deletion is blocked by current eligibility rules")
    return createDecision(reviewCase, DedupDecisionMode.SUGGESTED, keeper, losers, emptySet(), eligibility, actorId)
  }

  fun createManual(
    caseId: String,
    expectedRevision: Long,
    keeperBookId: String,
    removeBookIds: Set<String>,
    stateRevision: String,
    acknowledgedReasonCodes: Set<String>,
    actorId: String,
  ): DedupDecision {
    val reviewCase = currentCase(caseId, expectedRevision)
    if (keeperBookId !in reviewCase.memberBookIds || removeBookIds.isEmpty() || keeperBookId in removeBookIds || removeBookIds.any { it !in reviewCase.memberBookIds }) {
      conflict("PLAN_CHANGED", "Manual plan must retain one case member and remove only explicit other members")
    }
    val eligibility = eligibilityPolicy.evaluate(reviewCase, keeperBookId, removeBookIds)
    if (eligibility.stateRevision != stateRevision) conflict("STATE_CHANGED", "Local state changed after eligibility was displayed")
    if (!eligibility.manualDeleteEligible) conflict("MANUAL_NOT_ELIGIBLE", "Manual deletion is blocked by current consistency rules")
    val required =
      eligibility.warnings
        .filter { it.confirmationRequired && org.gotson.komga.interfaces.api.rest.dto.DedupAction.MANUAL in it.appliesTo }
        .map { it.code }
        .toSet()
    if (!acknowledgedReasonCodes.containsAll(required)) {
      conflict("ACKNOWLEDGEMENT_REQUIRED", "Manual confirmation is missing reason codes: ${(required - acknowledgedReasonCodes).sorted()}")
    }
    return createDecision(reviewCase, DedupDecisionMode.MANUAL, keeperBookId, removeBookIds, acknowledgedReasonCodes, eligibility, actorId)
  }

  fun requestExecution(decisionId: String): DedupDecision {
    val decision = decisionRepository.findDecision(decisionId) ?: conflict("DECISION_NOT_FOUND", "Decision not found")
    val token = UUID.randomUUID().toString()
    val allowed =
      when (decision.state) {
        DedupDecisionState.APPROVED -> setOf(DedupDecisionState.APPROVED)
        DedupDecisionState.NEEDS_ATTENTION -> {
          val retryable =
            decisionRepository
              .findDecisionItems(decision.id)
              .all { it.state in setOf(DedupDecisionItemState.PENDING, DedupDecisionItemState.CONFIRMED, DedupDecisionItemState.REAPPEARED, DedupDecisionItemState.FAILED) }
          if (!retryable) conflict("DECISION_NOT_RETRYABLE", "Decision contains a non-retryable conflict")
          setOf(DedupDecisionState.NEEDS_ATTENTION)
        }
        else -> conflict("DECISION_NOT_EXECUTABLE", "Decision state ${decision.state} cannot be executed")
      }
    if (!decisionRepository.claimDecision(decisionId, allowed, DedupDecisionState.REVALIDATING, token, LocalDateTime.now().plus(decisionLease))) {
      conflict("DECISION_ALREADY_CLAIMED", "Decision was claimed by another executor")
    }
    enqueueDecisionWork(decision)
    return requireNotNull(decisionRepository.findDecision(decisionId))
  }

  fun applyDecision(decisionId: String) {
    var decision = decisionRepository.findDecision(decisionId) ?: return
    val token = decision.executionToken ?: return
    if (decision.state !in setOf(DedupDecisionState.REVALIDATING, DedupDecisionState.PURGING)) return
    val items = decisionRepository.findDecisionItems(decisionId)
    if (decision.state == DedupDecisionState.REVALIDATING) {
      val failure = validatePlan(decision, items)
      if (failure != null) {
        finishValidationFailure(decision, token, items, failure)
        return
      }
      check(
        decisionRepository.updateDecisionState(
          decision.id,
          token,
          setOf(DedupDecisionState.REVALIDATING),
          DedupDecisionState.PURGING,
          resultJson = objectMapper.writeValueAsString(mapOf("code" to "PREFLIGHT_PASSED")),
        ),
      ) { "Decision lease changed during preflight" }
      decision = requireNotNull(decisionRepository.findDecision(decision.id))
    }

    var failure: ValidationFailure? = null
    for (item in decisionRepository.findDecisionItems(decision.id).filter { it.state in setOf(DedupDecisionItemState.PENDING, DedupDecisionItemState.REAPPEARED, DedupDecisionItemState.FAILED) }) {
      val validation = validateItem(decision, item, verifyPhysicalFile = true)
      if (validation != null) {
        failure = validation
        markItemFailure(decision, token, item, validation)
        break
      }
      check(
        decisionRepository.updateDecisionItem(
          item.id,
          decision.id,
          token,
          setOf(item.state),
          DedupDecisionItemState.REVALIDATING,
          incrementAttempt = true,
        ),
      ) { "Decision item changed before revalidation" }
      check(
        decisionRepository.updateDecisionItem(
          item.id,
          decision.id,
          token,
          setOf(DedupDecisionItemState.REVALIDATING),
          DedupDecisionItemState.READY_TO_DELETE,
        ),
      ) { "Decision item changed before deletion readiness" }
      check(
        decisionRepository.updateDecisionItem(
          item.id,
          decision.id,
          token,
          setOf(DedupDecisionItemState.READY_TO_DELETE),
          DedupDecisionItemState.DELETE_SUBMITTED,
        ),
      ) { "Decision item changed before physical deletion" }

      val book = requireNotNull(bookRepository.findByIdOrNull(item.bookId))
      val result = physicalDeletionLifecycle.deleteVerifiedBook(book, item.expectedIdentity())
      if (result.code != DedupDeletionResultCode.DELETED) {
        failure = ValidationFailure(result.code.name, item.bookId, reapprovalRequired = false, result.detail)
        check(
          decisionRepository.updateDecisionItem(
            item.id,
            decision.id,
            token,
            setOf(DedupDecisionItemState.DELETE_SUBMITTED),
            if (result.code == DedupDeletionResultCode.PATH_MISSING_UNCONFIRMED) DedupDecisionItemState.CONFLICT else DedupDecisionItemState.FAILED,
            resultCode = result.code.name,
            resultJson = objectMapper.writeValueAsString(result),
            lastError = result.detail,
          ),
        )
        break
      }
      val now = LocalDateTime.now()
      val settings = requireNotNull(dedupRepository.findLibrarySettings(item.libraryId))
      val stableAt = now.plusSeconds(settings.completionStabilitySeconds.toLong())
      check(
        decisionRepository.updateDecisionItem(
          item.id,
          decision.id,
          token,
          setOf(DedupDecisionItemState.DELETE_SUBMITTED),
          DedupDecisionItemState.DB_SOFT_DELETED,
          resultCode = result.code.name,
          resultJson = objectMapper.writeValueAsString(result),
          stabilityNotBefore = stableAt,
          deletedDate = now,
        ),
      ) { "Decision item changed after physical deletion" }
      decisionRepository.enqueueGorseSync(item.seriesId, item.libraryId, desiredHidden = true)
      enqueueVerificationWork(item, stableAt)
    }

    val latest = decisionRepository.findDecisionItems(decision.id)
    val anyDeleted = latest.any { it.state in setOf(DedupDecisionItemState.DB_SOFT_DELETED, DedupDecisionItemState.CONFIRMED) }
    val interrupted = latest.any { it.state in setOf(DedupDecisionItemState.REVALIDATING, DedupDecisionItemState.READY_TO_DELETE, DedupDecisionItemState.DELETE_SUBMITTED) }
    val nextState =
      when {
        failure?.reapprovalRequired == true -> DedupDecisionState.REAPPROVAL_REQUIRED
        failure != null && anyDeleted -> DedupDecisionState.PARTIALLY_COMPLETED
        failure != null -> DedupDecisionState.NEEDS_ATTENTION
        interrupted && anyDeleted -> DedupDecisionState.PARTIALLY_COMPLETED
        interrupted -> DedupDecisionState.NEEDS_ATTENTION
        else -> DedupDecisionState.PURGING
      }
    check(
      decisionRepository.updateDecisionState(
        decision.id,
        token,
        setOf(DedupDecisionState.PURGING),
        nextState,
        resultJson = objectMapper.writeValueAsString(mapOf("code" to (failure?.code ?: if (interrupted) "INTERRUPTED" else "STABILITY_PENDING"), "memberId" to failure?.memberId)),
        releaseLease = true,
      ),
    ) { "Decision lease changed after purge" }
  }

  fun verifyDeletion(itemId: String) {
    val item = decisionRepository.findDecisionItem(itemId) ?: return
    var decision = decisionRepository.findDecision(item.decisionId) ?: return
    if (item.state != DedupDecisionItemState.DB_SOFT_DELETED) return
    val token = UUID.randomUUID().toString()
    val claimable = setOf(DedupDecisionState.PURGING, DedupDecisionState.PARTIALLY_COMPLETED, DedupDecisionState.NEEDS_ATTENTION)
    if (!decisionRepository.claimDecision(decision.id, claimable, decision.state, token, LocalDateTime.now().plus(decisionLease))) return
    decision = requireNotNull(decisionRepository.findDecision(decision.id))
    val now = LocalDateTime.now()
    if (item.stabilityNotBefore?.isAfter(now) == true) {
      enqueueVerificationWork(item, item.stabilityNotBefore)
      decisionRepository.updateDecisionState(decision.id, token, setOf(decision.state), decision.state, releaseLease = true)
      return
    }

    val path =
      java.nio.file.Paths
        .get(item.expectedPath)
    val book = bookRepository.findByIdOrNull(item.bookId)
    val newItemState: DedupDecisionItemState
    val resultCode: DedupDeletionResultCode
    val resultDetail: String?
    if (java.nio.file.Files
        .exists(path)
    ) {
      val live = runCatching { book?.let { physicalDeletionLifecycle.captureStrongIdentity(it, requireDatabaseIdentity = false) } }.getOrNull()
      if (live?.archiveHash == item.expectedArchiveHash && live.size == item.expectedSize) {
        newItemState = DedupDecisionItemState.REAPPEARED
        resultCode = DedupDeletionResultCode.REAPPEARED_SAME_HASH
        resultDetail = "The expected archive reappeared after unlink; full plan revalidation is required before retry"
      } else {
        newItemState = DedupDecisionItemState.CONFLICT
        resultCode = DedupDeletionResultCode.REAPPEARED_DIFFERENT_HASH
        resultDetail = "A different file appeared at the deleted path and will not be removed by this decision"
      }
    } else if (book?.deletedDate != null) {
      newItemState = DedupDecisionItemState.CONFIRMED
      resultCode = DedupDeletionResultCode.CONFIRMED
      resultDetail = null
    } else {
      newItemState = DedupDecisionItemState.FAILED
      resultCode = DedupDeletionResultCode.DELETE_FAILED
      resultDetail = "The path is absent but Komga has not converged to a soft-deleted Book"
    }
    check(
      decisionRepository.updateDecisionItem(
        item.id,
        decision.id,
        token,
        setOf(DedupDecisionItemState.DB_SOFT_DELETED),
        newItemState,
        resultCode = resultCode.name,
        resultJson = objectMapper.writeValueAsString(mapOf("code" to resultCode.name, "detail" to resultDetail)),
        lastError = resultDetail,
        stabilityNotBefore = item.stabilityNotBefore,
        deletedDate = item.deletedDate,
      ),
    )

    val all = decisionRepository.findDecisionItems(decision.id)
    val finalState =
      when {
        all.all { it.state == DedupDecisionItemState.CONFIRMED } -> DedupDecisionState.COMPLETED
        all.any { it.state in setOf(DedupDecisionItemState.REAPPEARED, DedupDecisionItemState.CONFLICT, DedupDecisionItemState.FAILED) } &&
          all.any { it.state == DedupDecisionItemState.CONFIRMED } -> DedupDecisionState.PARTIALLY_COMPLETED
        all.any { it.state in setOf(DedupDecisionItemState.REAPPEARED, DedupDecisionItemState.CONFLICT, DedupDecisionItemState.FAILED) } -> DedupDecisionState.NEEDS_ATTENTION
        else -> DedupDecisionState.PURGING
      }
    check(
      decisionRepository.updateDecisionState(
        decision.id,
        token,
        setOf(decision.state),
        finalState,
        resultJson = objectMapper.writeValueAsString(mapOf("code" to resultCode.name, "remoteConfirmation" to "UNKNOWN", "gorseSync" to "PENDING")),
        releaseLease = true,
      ),
    )
  }

  fun reconcile(now: LocalDateTime = LocalDateTime.now()) {
    decisionRepository.releaseExpiredDecisionLeases(now)
    decisionRepository
      .findDecisionsByStates(setOf(DedupDecisionState.NEEDS_ATTENTION))
      .forEach { recoverInterruptedDecision(it, now) }
    decisionRepository
      .findDecisionsByStates(setOf(DedupDecisionState.REVALIDATING, DedupDecisionState.PURGING))
      .forEach { decision ->
        if (decision.executionToken != null) enqueueDecisionWork(decision)
        decisionRepository
          .findDecisionItems(decision.id)
          .filter { it.state == DedupDecisionItemState.DB_SOFT_DELETED }
          .forEach { item -> enqueueVerificationWork(item, item.stabilityNotBefore ?: now) }
      }
  }

  private fun recoverInterruptedDecision(
    decision: DedupDecision,
    now: LocalDateTime,
  ) {
    val interruptedStates =
      setOf(
        DedupDecisionItemState.REVALIDATING,
        DedupDecisionItemState.READY_TO_DELETE,
        DedupDecisionItemState.DELETE_SUBMITTED,
      )
    val allItems = decisionRepository.findDecisionItems(decision.id)
    val interrupted = allItems.filter { it.state in interruptedStates }
    val pendingVerification = allItems.filter { it.state == DedupDecisionItemState.DB_SOFT_DELETED }
    if (interrupted.isEmpty() && pendingVerification.isEmpty()) return
    val token = UUID.randomUUID().toString()
    if (!decisionRepository.claimDecision(decision.id, setOf(DedupDecisionState.NEEDS_ATTENTION), DedupDecisionState.NEEDS_ATTENTION, token, now.plus(decisionLease), now)) return
    var resumedPurging = pendingVerification.isNotEmpty()
    var hasConflict = false
    pendingVerification.forEach { enqueueVerificationWork(it, it.stabilityNotBefore ?: now) }
    interrupted.forEach { item ->
      val path =
        java.nio.file.Paths
          .get(item.expectedPath)
      val book = bookRepository.findByIdOrNull(item.bookId)
      when {
        item.state == DedupDecisionItemState.DELETE_SUBMITTED &&
          java.nio.file.Files
            .notExists(path) && book?.deletedDate != null -> {
          val settings = dedupRepository.findLibrarySettings(item.libraryId) ?: return@forEach
          val stableAt = now.plusSeconds(settings.completionStabilitySeconds.toLong())
          decisionRepository.updateDecisionItem(
            item.id,
            decision.id,
            token,
            setOf(DedupDecisionItemState.DELETE_SUBMITTED),
            DedupDecisionItemState.DB_SOFT_DELETED,
            resultCode = DedupDeletionResultCode.ALREADY_DELETED_BY_THIS_DECISION.name,
            resultJson = objectMapper.writeValueAsString(mapOf("code" to "RECOVERED_AFTER_UNLINK")),
            stabilityNotBefore = stableAt,
            deletedDate = now,
          )
          enqueueVerificationWork(item, stableAt)
          resumedPurging = true
        }

        java.nio.file.Files
          .exists(path) -> {
          val live = runCatching { book?.let { physicalDeletionLifecycle.captureStrongIdentity(it, requireDatabaseIdentity = false) } }.getOrNull()
          val unchanged = live?.matches(item.expectedIdentity()) == true
          decisionRepository.updateDecisionItem(
            item.id,
            decision.id,
            token,
            setOf(item.state),
            if (unchanged) DedupDecisionItemState.FAILED else DedupDecisionItemState.CONFLICT,
            resultCode = if (unchanged) "LEASE_EXPIRED_BEFORE_UNLINK" else DedupDeletionResultCode.GENERATION_MISMATCH.name,
            resultJson = objectMapper.writeValueAsString(mapOf("code" to if (unchanged) "SAFE_TO_RETRY" else "FILE_CHANGED_DURING_INTERRUPTION")),
          )
          if (!unchanged) hasConflict = true
        }

        else -> {
          decisionRepository.updateDecisionItem(
            item.id,
            decision.id,
            token,
            setOf(item.state),
            DedupDecisionItemState.CONFLICT,
            resultCode = DedupDeletionResultCode.PATH_MISSING_UNCONFIRMED.name,
            resultJson = objectMapper.writeValueAsString(mapOf("code" to "PATH_MISSING_AFTER_INTERRUPTION")),
          )
          hasConflict = true
        }
      }
    }
    decisionRepository.updateDecisionState(
      decision.id,
      token,
      setOf(DedupDecisionState.NEEDS_ATTENTION),
      when {
        resumedPurging && hasConflict -> DedupDecisionState.PARTIALLY_COMPLETED
        resumedPurging -> DedupDecisionState.PURGING
        else -> DedupDecisionState.NEEDS_ATTENTION
      },
      resultJson = objectMapper.writeValueAsString(mapOf("code" to "INTERRUPTED_SAGA_RECONCILED")),
      releaseLease = true,
    )
  }

  private fun createDecision(
    reviewCase: DedupReviewCase,
    mode: DedupDecisionMode,
    keeperBookId: String,
    removeBookIds: Set<String>,
    acknowledgedReasonCodes: Set<String>,
    eligibility: org.gotson.komga.interfaces.api.rest.dto.DedupEligibilityReportDto,
    actorId: String,
  ): DedupDecision {
    if (decisionRepository.hasActiveDecisionForBooks(reviewCase.memberBookIds)) conflict("DELETION_IN_PROGRESS", "A case member already belongs to an active decision")
    val keeper = bookRepository.findByIdOrNull(keeperBookId) ?: conflict("KEEPER_UNHEALTHY", "Keeper no longer exists")
    val keeperSource = coverLifecycle.currentSourceIdentity(keeperBookId) ?: conflict("KEEPER_UNHEALTHY", "Keeper is outside the current single-Book scope")
    val keeperIdentity = physicalDeletionLifecycle.captureStrongIdentity(keeper)
    val keeperSnapshot = DedupKeeperSnapshot(keeper.id, keeper.seriesId, keeper.libraryId, keeperSource.contentGeneration, keeperSource.seriesScopeRevision, keeperIdentity)
    val now = LocalDateTime.now()
    val decisionId = TsidCreator.getTsid256().toString()
    val relations = mutableListOf<DedupRelation>()
    val items =
      removeBookIds.sorted().map { bookId ->
        val book = bookRepository.findByIdOrNull(bookId) ?: conflict("MEMBER_DELETED", "Removal member no longer exists")
        val source = coverLifecycle.currentSourceIdentity(bookId) ?: conflict("OUT_OF_SCOPE_MULTI_BOOK_SERIES", "Removal member is outside the current single-Book scope")
        val relation = dedupRepository.findRelation(bookId, keeperBookId) ?: conflict("DIRECT_KEEPER_RELATION_MISSING", "A direct relation to the keeper is required")
        relations += relation
        val identity = physicalDeletionLifecycle.captureStrongIdentity(book)
        val localState = localStateLifecycle.snapshot(bookId)
        DedupDecisionItem(
          id = TsidCreator.getTsid256().toString(),
          decisionId = decisionId,
          bookId = book.id,
          seriesId = book.seriesId,
          libraryId = book.libraryId,
          titleSnapshot = book.name,
          pathSnapshot = identity.path,
          expectedPath = identity.path,
          expectedSize = identity.size,
          expectedMtime = identity.mtime,
          expectedArchiveHash = identity.archiveHash,
          sourceContentGeneration = source.contentGeneration,
          seriesScopeRevision = source.seriesScopeRevision,
          stateRevision = localState.revision,
          acknowledgedReasonsJson = objectMapper.writeValueAsString(acknowledgedReasonCodes.sorted()),
          directRelationId = relation.id,
          directRelationGenerations = relation.planIdentity(),
          state = DedupDecisionItemState.PENDING,
          attemptCount = 0,
          resultCode = null,
          resultJson = null,
          lastError = null,
          stabilityNotBefore = null,
          deletedDate = null,
          createdDate = now,
          lastModifiedDate = now,
        )
      }
    val planRevision = eligibility.planRevision ?: conflict("PLAN_CHANGED", "Plan revision is unavailable")
    val decision =
      DedupDecision(
        id = decisionId,
        reviewCaseId = reviewCase.id,
        planRevision = planRevision,
        mode = mode,
        keeperBookId = keeperBookId,
        keeperSnapshotJson = objectMapper.writeValueAsString(keeperSnapshot),
        planJson =
          objectMapper.writeValueAsString(
            mapOf(
              "reviewCaseRevision" to reviewCase.revision,
              "keeperBookId" to keeperBookId,
              "removeBookIds" to removeBookIds.sorted(),
              "irreversible" to true,
              "atomic" to false,
            ),
          ),
        evidenceJson = objectMapper.writeValueAsString(relations),
        eligibilityJson = objectMapper.writeValueAsString(eligibility),
        classifierRuleVersion = DedupEligibilityPolicy.RULE_VERSION,
        manualConfirmationJson =
          if (mode == DedupDecisionMode.MANUAL) {
            objectMapper.writeValueAsString(mapOf("actorId" to actorId, "confirmedAt" to now, "acknowledgedReasonCodes" to acknowledgedReasonCodes.sorted()))
          } else {
            null
          },
        state = DedupDecisionState.APPROVED,
        actorId = actorId,
        approvedDate = now,
        executedDate = null,
        completedDate = null,
        createdDate = now,
        lastModifiedDate = now,
      )
    decisionRepository.insertDecision(decision, items)
    return decision
  }

  private fun validatePlan(
    decision: DedupDecision,
    items: List<DedupDecisionItem>,
  ): ValidationFailure? {
    val keeper = validateKeeper(decision)
    if (keeper != null) return keeper
    for (item in items.filter { it.state != DedupDecisionItemState.CONFIRMED }) {
      val failure = validateItem(decision, item, verifyPhysicalFile = true)
      if (failure != null) return failure
    }
    return null
  }

  private fun validateKeeper(decision: DedupDecision): ValidationFailure? {
    val snapshot = objectMapper.readValue(decision.keeperSnapshotJson, DedupKeeperSnapshot::class.java)
    val book = bookRepository.findByIdOrNull(snapshot.bookId) ?: return ValidationFailure("KEEPER_UNHEALTHY", snapshot.bookId, false, "Keeper no longer exists")
    if (book.deletedDate != null) return ValidationFailure("KEEPER_UNHEALTHY", snapshot.bookId, false, "Keeper is soft-deleted")
    val current = coverLifecycle.currentSourceIdentity(snapshot.bookId) ?: return ValidationFailure("SCOPE_CHANGED", snapshot.bookId, false, "Keeper scope changed")
    if (current.contentGeneration != snapshot.contentGeneration || current.seriesScopeRevision != snapshot.seriesScopeRevision) {
      return ValidationFailure("KEEPER_UNHEALTHY", snapshot.bookId, false, "Keeper content or scope changed")
    }
    val live = runCatching { physicalDeletionLifecycle.captureStrongIdentity(book) }.getOrElse { return ValidationFailure("KEEPER_UNHEALTHY", snapshot.bookId, false, it.message) }
    if (!live.matches(snapshot.file)) return ValidationFailure("KEEPER_UNHEALTHY", snapshot.bookId, false, "Keeper physical identity changed")
    return null
  }

  private fun validateItem(
    decision: DedupDecision,
    item: DedupDecisionItem,
    verifyPhysicalFile: Boolean,
  ): ValidationFailure? {
    validateKeeper(decision)?.let { return it }
    val book = bookRepository.findByIdOrNull(item.bookId) ?: return ValidationFailure("MEMBER_DELETED", item.bookId, false, "Removal member no longer exists")
    if (book.deletedDate != null) return ValidationFailure("MEMBER_DELETED", item.bookId, false, "Removal member is already soft-deleted")
    val source = coverLifecycle.currentSourceIdentity(item.bookId) ?: return ValidationFailure("SCOPE_CHANGED", item.bookId, false, "Removal member scope changed")
    if (source.contentGeneration != item.sourceContentGeneration) return ValidationFailure("GENERATION_MISMATCH", item.bookId, false, "Removal member content changed")
    if (source.seriesScopeRevision != item.seriesScopeRevision) return ValidationFailure("SCOPE_CHANGED", item.bookId, false, "Removal member series scope changed")
    val relation = dedupRepository.findRelation(item.bookId, decision.keeperBookId) ?: return ValidationFailure("RELATION_CHANGED", item.bookId, false, "Direct keeper relation disappeared")
    if (relation.id != item.directRelationId || relation.planIdentity() != item.directRelationGenerations) {
      return ValidationFailure("RELATION_CHANGED", item.bookId, false, "Direct keeper evidence changed")
    }
    val state = localStateLifecycle.snapshot(item.bookId)
    if (state.revision != item.stateRevision) return ValidationFailure("STATE_CHANGED", item.bookId, true, "Local user state changed after approval")
    if (decision.mode == DedupDecisionMode.SUGGESTED && state.reasonCodes.isNotEmpty()) {
      return ValidationFailure("STATE_CHANGED", item.bookId, true, "Suggested deletion no longer has an empty local-state snapshot")
    }
    if (verifyPhysicalFile) {
      val identity = runCatching { physicalDeletionLifecycle.captureStrongIdentity(book) }.getOrElse { return ValidationFailure("GENERATION_MISMATCH", item.bookId, false, it.message) }
      if (!identity.matches(item.expectedIdentity())) return ValidationFailure("GENERATION_MISMATCH", item.bookId, false, "Removal member physical identity changed")
    }
    return null
  }

  private fun finishValidationFailure(
    decision: DedupDecision,
    token: String,
    items: List<DedupDecisionItem>,
    failure: ValidationFailure,
  ) {
    val anyDeleted = items.any { it.state in setOf(DedupDecisionItemState.DB_SOFT_DELETED, DedupDecisionItemState.CONFIRMED) }
    val state =
      when {
        failure.reapprovalRequired -> DedupDecisionState.REAPPROVAL_REQUIRED
        anyDeleted -> DedupDecisionState.PARTIALLY_COMPLETED
        else -> DedupDecisionState.ABORTED
      }
    check(
      decisionRepository.updateDecisionState(
        decision.id,
        token,
        setOf(DedupDecisionState.REVALIDATING),
        state,
        resultJson = objectMapper.writeValueAsString(failure),
        releaseLease = true,
      ),
    )
  }

  private fun markItemFailure(
    decision: DedupDecision,
    token: String,
    item: DedupDecisionItem,
    failure: ValidationFailure,
  ) {
    check(
      decisionRepository.updateDecisionItem(
        item.id,
        decision.id,
        token,
        setOf(item.state),
        DedupDecisionItemState.CONFLICT,
        resultCode = failure.code,
        resultJson = objectMapper.writeValueAsString(failure),
        lastError = failure.detail,
      ),
    )
  }

  private fun currentCase(
    caseId: String,
    expectedRevision: Long,
  ): DedupReviewCase {
    val reviewCase = dedupRepository.findReviewCase(caseId) ?: conflict("CASE_NOT_FOUND", "Review case not found")
    if (reviewCase.revision != expectedRevision) conflict("PLAN_CHANGED", "Review case changed after it was displayed")
    return reviewCase
  }

  private fun enqueueDecisionWork(decision: DedupDecision) {
    val libraryId = decisionRepository.findDecisionItems(decision.id).first().libraryId
    dedupRepository.enqueueWork(
      id = TsidCreator.getTsid256().toString(),
      libraryId = libraryId,
      type = DedupWorkType.APPLY_DECISION_ITEM,
      targetKey = decision.id,
      priority = HIGH_PRIORITY,
    )
    taskEmitter.drainDedupQueue(libraryId, HIGH_PRIORITY)
  }

  private fun enqueueVerificationWork(
    item: DedupDecisionItem,
    notBefore: LocalDateTime,
  ) {
    dedupRepository.enqueueWork(
      id = TsidCreator.getTsid256().toString(),
      libraryId = item.libraryId,
      type = DedupWorkType.VERIFY_DELETION,
      targetKey = item.id,
      notBefore = notBefore,
      priority = HIGH_PRIORITY,
    )
    taskEmitter.drainDedupQueue(item.libraryId, HIGH_PRIORITY)
  }

  private fun DedupDecisionItem.expectedIdentity() = DedupStrongFileIdentity(expectedPath, expectedSize, expectedMtime, expectedArchiveHash)

  private fun DedupRelation.planIdentity(): String = "$id:$bookLowId:$bookHighId:$lowContentGeneration:$highContentGeneration:$type:$containedBookId:$containerBookId:$classifierRuleVersion"

  private fun conflict(
    code: String,
    message: String,
  ): Nothing = throw DedupDecisionValidationException(code, message)

  private data class ValidationFailure(
    val code: String,
    val memberId: String,
    val reapprovalRequired: Boolean,
    val detail: String?,
  )
}
