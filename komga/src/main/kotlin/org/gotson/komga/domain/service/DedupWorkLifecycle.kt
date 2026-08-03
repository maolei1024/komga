package org.gotson.komga.domain.service

import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.application.tasks.DEFAULT_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupWork
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.infrastructure.gorse.GorseDesiredStateLifecycle
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

@Service
class DedupWorkLifecycle(
  private val dedupRepository: DedupRepository,
  private val exactDuplicateLifecycle: DedupExactDuplicateLifecycle,
  private val coverLifecycle: DedupCoverLifecycle,
  private val deepVerificationLifecycle: DedupDeepVerificationLifecycle,
  private val decisionLifecycle: DedupDecisionLifecycle,
  private val taskEmitter: TaskEmitter,
  private val gorseDesiredStateLifecycle: GorseDesiredStateLifecycle,
) {
  private val leaseDuration = Duration.ofMinutes(10)

  fun saveSettings(settings: DedupLibrarySettings) {
    dedupRepository.saveLibrarySettings(settings)
    if (settings.enabled && !settings.paused) requestExactReconciliation(settings.libraryId, bypassQuietPeriod = true)
  }

  fun requestExactReconciliation(
    libraryId: String,
    changedAt: LocalDateTime = LocalDateTime.now(),
    bypassQuietPeriod: Boolean = false,
    priority: Int = DEFAULT_PRIORITY,
  ): DedupWork? {
    val settings = dedupRepository.findLibrarySettings(libraryId) ?: return null
    if (!settings.enabled) return null
    val notBefore = if (bypassQuietPeriod) changedAt else changedAt.plusSeconds(settings.quietPeriodSeconds.toLong())
    val work =
      dedupRepository.enqueueWork(
        id = TsidCreator.getTsid256().toString(),
        libraryId = libraryId,
        type = DedupWorkType.RECONCILE_EXACT_DUPLICATES,
        notBefore = notBefore,
        priority = priority,
      )
    taskEmitter.drainDedupQueue(libraryId, priority)
    return work
  }

  fun drain(libraryId: String) {
    val settings = dedupRepository.findLibrarySettings(libraryId) ?: return
    val allowedTypes =
      if (settings.enabled && !settings.paused) {
        null
      } else {
        setOf(DedupWorkType.APPLY_DECISION_ITEM, DedupWorkType.VERIFY_DELETION)
      }

    val started = LocalDateTime.now()
    var processed = 0
    while (processed < settings.batchSize && Duration.between(started, LocalDateTime.now()).seconds < settings.maxDurationSeconds) {
      val work =
        dedupRepository.claimNextWork(
          owner = Thread.currentThread().name,
          leaseDuration = leaseDuration,
          libraryId = libraryId,
          allowedTypes = allowedTypes,
        ) ?: break
      process(work)
      processed++
    }
  }

  fun reconcileAtStartup() {
    reconcileScheduled()
  }

  fun reconcileScheduled(now: LocalDateTime = LocalDateTime.now()) {
    dedupRepository.releaseExpiredLeases(now)
    decisionLifecycle.reconcile(now)
    gorseDesiredStateLifecycle.reconcile()
    val workByLibrary =
      dedupRepository
        .findAllWork()
        .filter { it.type == DedupWorkType.RECONCILE_EXACT_DUPLICATES }
        .associateBy { it.libraryId }

    dedupRepository
      .findAllLibrarySettings()
      .filter { it.enabled && !it.paused }
      .forEach { settings ->
        val existing = workByLibrary[settings.libraryId]
        when {
          existing == null -> requestExactReconciliation(settings.libraryId, now, bypassQuietPeriod = true)
          existing.state in setOf(DedupWorkState.WAITING, DedupWorkState.PENDING) -> taskEmitter.drainDedupQueue(settings.libraryId)
          existing.state == DedupWorkState.SUCCEEDED &&
            (existing.completedDate == null || !existing.completedDate.plus(settings.scanInterval.toDuration()).isAfter(now)) ->
            requestExactReconciliation(settings.libraryId, now, bypassQuietPeriod = true)
        }
      }

    dedupRepository
      .findAllWork()
      .filter { it.state in setOf(DedupWorkState.WAITING, DedupWorkState.PENDING) && !it.notBefore.isAfter(now) && (it.nextRetryAt == null || !it.nextRetryAt.isAfter(now)) }
      .map { it.libraryId }
      .distinct()
      .forEach(taskEmitter::drainDedupQueue)
  }

  fun retry(workId: String): Boolean {
    val work = dedupRepository.findWorkById(workId) ?: return false
    if (!dedupRepository.retryFailedWork(workId)) return false
    taskEmitter.drainDedupQueue(work.libraryId, DEFAULT_PRIORITY)
    return true
  }

  fun requestCaseVerification(caseId: String): DedupWork? {
    val reviewCase = dedupRepository.findReviewCase(caseId) ?: return null
    val work =
      dedupRepository.enqueueWork(
        id = TsidCreator.getTsid256().toString(),
        libraryId = reviewCase.libraryId,
        type = DedupWorkType.VERIFY_RELATION,
        targetKey = caseId,
        priority = 6,
      )
    taskEmitter.drainDedupQueue(reviewCase.libraryId, 6)
    return work
  }

  private fun process(work: DedupWork) {
    val leaseToken = requireNotNull(work.leaseToken)
    try {
      when (work.type) {
        DedupWorkType.RECONCILE_EXACT_DUPLICATES -> {
          exactDuplicateLifecycle.reconcileLibrary(work.libraryId)
          coverLifecycle.findDirtyBookIds(work.libraryId).forEach { bookId ->
            dedupRepository.enqueueWork(
              id = TsidCreator.getTsid256().toString(),
              libraryId = work.libraryId,
              type = DedupWorkType.COMPUTE_COVER,
              targetKey = bookId,
              priority = 2,
            )
          }
          dedupRepository.enqueueWork(
            id = TsidCreator.getTsid256().toString(),
            libraryId = work.libraryId,
            type = DedupWorkType.FIND_COVER_NEIGHBORS,
            priority = 0,
          )
        }

        DedupWorkType.COMPUTE_COVER -> coverLifecycle.computeCover(work.targetKey)
        DedupWorkType.FIND_COVER_NEIGHBORS -> coverLifecycle.rebuildCandidates(work.libraryId)
        DedupWorkType.VERIFY_RELATION -> deepVerificationLifecycle.verifyCase(work.targetKey)
        DedupWorkType.APPLY_DECISION_ITEM -> decisionLifecycle.applyDecision(work.targetKey)
        DedupWorkType.VERIFY_DELETION -> decisionLifecycle.verifyDeletion(work.targetKey)
      }
      check(dedupRepository.completeWork(work.id, leaseToken, work.desiredRevision)) {
        "Dedup work lease changed before completion"
      }
    } catch (exception: Exception) {
      logger.error(exception) { "Dedup work ${work.id} (${work.type}) failed" }
      dedupRepository.failWork(
        workId = work.id,
        leaseToken = leaseToken,
        attemptedRevision = work.desiredRevision,
        errorCode = exception.javaClass.simpleName.uppercase(),
        sanitizedError = exception.message?.replace(Regex("[\\r\\n]+"), " ") ?: exception.javaClass.simpleName,
      )
    }
  }

  private fun Library.ScanInterval.toDuration(): Duration =
    when (this) {
      Library.ScanInterval.DISABLED -> error("Dedup settings cannot use a disabled interval")
      Library.ScanInterval.HOURLY -> Duration.ofHours(1)
      Library.ScanInterval.EVERY_6H -> Duration.ofHours(6)
      Library.ScanInterval.EVERY_12H -> Duration.ofHours(12)
      Library.ScanInterval.DAILY -> Duration.ofDays(1)
      Library.ScanInterval.WEEKLY -> Duration.ofDays(7)
    }
}
