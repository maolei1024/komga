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
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.gotson.komga.infrastructure.gorse.GorseDesiredStateLifecycle
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

@Service
class DedupWorkLifecycle(
  private val dedupRepository: DedupRepository,
  private val resolutionRepository: DedupResolutionRepository,
  private val bookRepository: BookRepository,
  private val exactDuplicateLifecycle: DedupExactDuplicateLifecycle,
  private val coverLifecycle: DedupCoverLifecycle,
  private val deepVerificationLifecycle: DedupDeepVerificationLifecycle,
  private val clusterLifecycle: DedupClusterLifecycle,
  private val taskEmitter: TaskEmitter,
  private val gorseDesiredStateLifecycle: GorseDesiredStateLifecycle,
) {
  companion object {
    const val PRIORITY_UNSCANNED = 0
    const val PRIORITY_ADDED = 10
    const val PRIORITY_UPDATED = 20
    const val PRIORITY_DELETED = 30
    private const val PRIORITY_VERIFY = 6
    private const val PRIORITY_REBUILD = -1
  }

  private val leaseDuration = Duration.ofMinutes(10)

  fun saveSettings(settings: DedupLibrarySettings) {
    dedupRepository.saveLibrarySettings(settings)
    if (settings.enabled && !settings.paused) requestLibraryBatch(settings.libraryId)
  }

  /** Book events only coalesce desired work. A Library/manual/scheduled batch owns draining. */
  fun requestBookScan(
    libraryId: String,
    bookId: String,
    priority: Int,
    changedAt: LocalDateTime = LocalDateTime.now(),
  ): DedupWork? {
    val settings = dedupRepository.findLibrarySettings(libraryId) ?: return null
    if (!settings.enabled) return null
    return dedupRepository.enqueueWork(
      id = TsidCreator.getTsid256().toString(),
      libraryId = libraryId,
      type = DedupWorkType.SCAN_BOOK,
      targetKey = bookId,
      notBefore = changedAt.plusSeconds(settings.quietPeriodSeconds.toLong()),
      priority = priority,
    )
  }

  fun requestLibraryBatch(
    libraryId: String,
    priority: Int = DEFAULT_PRIORITY,
  ): Boolean {
    val settings = dedupRepository.findLibrarySettings(libraryId) ?: return false
    if (!settings.enabled || settings.paused) return false
    taskEmitter.drainDedupQueue(libraryId, priority)
    return true
  }

  fun drain(libraryId: String) {
    val settings = dedupRepository.findLibrarySettings(libraryId) ?: return
    val started = LocalDateTime.now()
    var scannedBooks = 0
    var rebuildRequested = false

    if (settings.enabled && !settings.paused) {
      // One immutable snapshot per batch; each scanned Book is then incrementally upserted.
      coverLifecycle.rebuildIndex(libraryId)
      while (scannedBooks < settings.batchSize && withinBudget(started, settings)) {
        var work = claim(libraryId, setOf(DedupWorkType.SCAN_BOOK))
        if (work == null) {
          val missing =
            dedupRepository.findUnscannedBookIds(
              libraryId,
              DedupCoverLifecycle.FEATURE_SCHEMA_VERSION,
              settings.batchSize - scannedBooks,
            )
          if (missing.isEmpty()) break
          missing.forEach { bookId ->
            dedupRepository.enqueueWork(
              id = TsidCreator.getTsid256().toString(),
              libraryId = libraryId,
              type = DedupWorkType.SCAN_BOOK,
              targetKey = bookId,
              priority = PRIORITY_UNSCANNED,
            )
          }
          work = claim(libraryId, setOf(DedupWorkType.SCAN_BOOK)) ?: break
        }
        scannedBooks++
        if (processScan(work)) rebuildRequested = true
      }
      if (rebuildRequested) enqueueClusterRebuild(libraryId)
      dedupRepository.updateLibraryBatchResult(libraryId, scannedBooks)
    }

    // Internal verification/rebuild work never consumes the N-Book allowance.
    while (withinBudget(started, settings)) {
      val work = claim(libraryId, setOf(DedupWorkType.VERIFY_RELATION, DedupWorkType.REBUILD_CLUSTERS)) ?: break
      processInternal(work)
    }
  }

  fun reconcileAtStartup() = reconcileScheduled()

  fun reconcileScheduled(now: LocalDateTime = LocalDateTime.now()) {
    dedupRepository.releaseExpiredLeases(now)
    resolutionRepository.releaseExpiredResolutionLeases(now)
    gorseDesiredStateLifecycle.reconcile()
    val queuedLibraries =
      dedupRepository
        .findAllWork()
        .filter {
          it.state in setOf(DedupWorkState.WAITING, DedupWorkState.PENDING) &&
            !it.notBefore.isAfter(now) &&
            (it.nextRetryAt == null || !it.nextRetryAt.isAfter(now))
        }.map { it.libraryId }
        .toSet()
    dedupRepository.findAllLibrarySettings().filter { it.enabled && !it.paused }.forEach { settings ->
      val due = settings.lastBatchDate?.plus(settings.scanInterval.toDuration())?.isAfter(now) != true
      if (due || settings.libraryId in queuedLibraries) taskEmitter.drainDedupQueue(settings.libraryId)
    }
  }

  fun retry(workId: String): Boolean {
    val work = dedupRepository.findWorkById(workId) ?: return false
    if (!dedupRepository.retryFailedWork(workId)) return false
    taskEmitter.drainDedupQueue(work.libraryId, DEFAULT_PRIORITY)
    return true
  }

  private fun claim(
    libraryId: String,
    types: Set<DedupWorkType>,
  ): DedupWork? = dedupRepository.claimNextWork(Thread.currentThread().name, leaseDuration, libraryId, types)

  private fun processScan(work: DedupWork): Boolean {
    val leaseToken = requireNotNull(work.leaseToken)
    return try {
      val book = bookRepository.findByIdOrNull(work.targetKey)
      if (book == null || book.deletedDate != null || !book.url.path.endsWith(".cbz", ignoreCase = true)) {
        coverLifecycle.cleanupBook(work.libraryId, work.targetKey)
      } else {
        requireNotNull(coverLifecycle.computeCover(book.id)) { "Book became ineligible during cover analysis" }
        exactDuplicateLifecycle.refreshForBook(book.id)
        coverLifecycle.refreshCandidatesForBook(book.id).forEach { (low, high) ->
          dedupRepository.enqueueWork(
            id = TsidCreator.getTsid256().toString(),
            libraryId = work.libraryId,
            type = DedupWorkType.VERIFY_RELATION,
            targetKey = "$low|$high",
            priority = PRIORITY_VERIFY,
          )
        }
      }
      check(dedupRepository.completeWork(work.id, leaseToken, work.desiredRevision)) { "Dedup work lease changed before completion" }
      true
    } catch (exception: Exception) {
      fail(work, exception)
      false
    }
  }

  private fun processInternal(work: DedupWork) {
    val leaseToken = requireNotNull(work.leaseToken)
    try {
      when (work.type) {
        DedupWorkType.VERIFY_RELATION -> {
          val ids = work.targetKey.split('|')
          require(ids.size == 2 && ids[0] < ids[1]) { "Invalid relation work target" }
          deepVerificationLifecycle.verifyRelation(ids[0], ids[1])
          enqueueClusterRebuild(work.libraryId)
        }
        DedupWorkType.REBUILD_CLUSTERS -> clusterLifecycle.rebuildLibrary(work.libraryId)
        DedupWorkType.SCAN_BOOK -> error("SCAN_BOOK must be processed by the bounded scan phase")
      }
      check(dedupRepository.completeWork(work.id, leaseToken, work.desiredRevision)) { "Dedup work lease changed before completion" }
    } catch (exception: Exception) {
      fail(work, exception)
    }
  }

  private fun enqueueClusterRebuild(libraryId: String) {
    dedupRepository.enqueueWork(
      id = TsidCreator.getTsid256().toString(),
      libraryId = libraryId,
      type = DedupWorkType.REBUILD_CLUSTERS,
      priority = PRIORITY_REBUILD,
    )
  }

  private fun fail(
    work: DedupWork,
    exception: Exception,
  ) {
    logger.error(exception) { "Dedup work ${work.id} (${work.type}) failed" }
    dedupRepository.failWork(
      work.id,
      requireNotNull(work.leaseToken),
      work.desiredRevision,
      exception.javaClass.simpleName
        .uppercase()
        .take(100),
      (exception.message ?: exception.javaClass.simpleName).replace(Regex("[\\r\\n]+"), " ").take(500),
    )
  }

  private fun withinBudget(
    started: LocalDateTime,
    settings: DedupLibrarySettings,
  ): Boolean = Duration.between(started, LocalDateTime.now()).seconds < settings.maxDurationSeconds

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
