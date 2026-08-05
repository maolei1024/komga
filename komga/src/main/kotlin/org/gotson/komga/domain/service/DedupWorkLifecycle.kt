package org.gotson.komga.domain.service

import com.github.f4b6a3.tsid.TsidCreator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.application.tasks.DEFAULT_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.DedupArchiveHashState
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupWork
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.gotson.komga.infrastructure.gorse.GorseDesiredStateLifecycle
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime

private val logger = KotlinLogging.logger {}

data class DedupClusterVerificationRequest(
  val clusterId: String,
  val expectedRevision: Long,
)

data class DedupClusterVerificationResult(
  val clusterId: String,
  val status: DedupClusterVerificationStatus,
  val memberCount: Int = 0,
  val pairCount: Int = 0,
  val queuedPairs: Int = 0,
  val skippedPairs: Int = 0,
  val failedPairs: Int = 0,
)

enum class DedupClusterVerificationStatus {
  QUEUED,
  STALE,
  NOT_FOUND,
  NO_ELIGIBLE_PAIR,
}

@Service
class DedupWorkLifecycle(
  private val dedupRepository: DedupRepository,
  private val resolutionRepository: DedupResolutionRepository,
  private val exactDuplicateLifecycle: DedupExactDuplicateLifecycle,
  private val coverLifecycle: DedupCoverLifecycle,
  private val deepVerificationLifecycle: DedupDeepVerificationLifecycle,
  private val clusterLifecycle: DedupClusterLifecycle,
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
    val value = dedupRepository.enqueueWork(TsidCreator.getTsid256().toString(), libraryId, DedupWorkType.RECONCILE_EXACT_DUPLICATES, notBefore = notBefore, priority = priority)
    taskEmitter.drainDedupQueue(libraryId, priority)
    return value
  }

  fun drain(libraryId: String) {
    val settings = dedupRepository.findLibrarySettings(libraryId) ?: return
    val allowedTypes =
      if (settings.enabled && !settings.paused) null else setOf(DedupWorkType.VERIFY_RELATION, DedupWorkType.REBUILD_CLUSTERS)
    val started = LocalDateTime.now()
    var processed = 0
    while (processed < settings.batchSize && Duration.between(started, LocalDateTime.now()).seconds < settings.maxDurationSeconds) {
      val work = dedupRepository.claimNextWork(Thread.currentThread().name, leaseDuration, libraryId, allowedTypes) ?: break
      process(work)
      processed++
    }
  }

  fun reconcileAtStartup() = reconcileScheduled()

  fun reconcileScheduled(now: LocalDateTime = LocalDateTime.now()) {
    dedupRepository.releaseExpiredLeases(now)
    resolutionRepository.releaseExpiredResolutionLeases(now)
    gorseDesiredStateLifecycle.reconcile()
    val reconciliation = dedupRepository.findAllWork().filter { it.type == DedupWorkType.RECONCILE_EXACT_DUPLICATES }.associateBy { it.libraryId }
    dedupRepository.findAllLibrarySettings().filter { it.enabled && !it.paused }.forEach { settings ->
      val existing = reconciliation[settings.libraryId]
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

  fun requestClusterVerification(
    clusterId: String,
    expectedRevision: Long,
  ): DedupClusterVerificationResult {
    val result = enqueueClusterVerification(DedupClusterVerificationRequest(clusterId, expectedRevision))
    val cluster = dedupRepository.findCluster(clusterId)
    if (result.queuedPairs > 0 && cluster != null) taskEmitter.drainDedupQueue(cluster.cluster.libraryId, 6)
    return result
  }

  fun requestClusterVerifications(requests: List<DedupClusterVerificationRequest>): List<DedupClusterVerificationResult> {
    val libraries = mutableSetOf<String>()
    val results =
      requests.map { request ->
        enqueueClusterVerification(request).also { result ->
          if (result.queuedPairs > 0)
            dedupRepository
              .findCluster(request.clusterId)
              ?.cluster
              ?.libraryId
              ?.let(libraries::add)
        }
      }
    libraries.forEach { taskEmitter.drainDedupQueue(it, 6) }
    return results
  }

  private fun enqueueClusterVerification(request: DedupClusterVerificationRequest): DedupClusterVerificationResult {
    val value =
      dedupRepository.findCluster(request.clusterId)
        ?: return DedupClusterVerificationResult(request.clusterId, DedupClusterVerificationStatus.NOT_FOUND)
    val memberIds =
      value.members
        .filter { it.present }
        .map { it.bookId }
        .sorted()
    if (value.cluster.revision != request.expectedRevision || !value.cluster.reviewable) {
      return DedupClusterVerificationResult(request.clusterId, DedupClusterVerificationStatus.STALE, memberIds.size)
    }
    val pairs = memberIds.flatMapIndexed { index, left -> memberIds.drop(index + 1).map { right -> left to right } }
    var queued = 0
    var skipped = 0
    var failed = 0
    pairs.forEach { (left, right) ->
      val relation = dedupRepository.findRelation(left, right)
      val identities =
        if (relation?.type == DedupRelationType.EXACT_FILE) {
          listOfNotNull(coverLifecycle.currentSourceIdentity(left), coverLifecycle.currentSourceIdentity(right)).associateBy { it.bookId }
        } else {
          emptyMap()
        }
      val exactIdentityReady =
        relation?.type == DedupRelationType.EXACT_FILE &&
          identities.size == 2 &&
          identities.values.all { it.archiveHashState == DedupArchiveHashState.READY } &&
          relation.isCurrent(identities)
      if (exactIdentityReady) {
        skipped++
      } else {
        runCatching {
          dedupRepository.enqueueWork(
            id = TsidCreator.getTsid256().toString(),
            libraryId = value.cluster.libraryId,
            type = DedupWorkType.VERIFY_RELATION,
            targetKey = "$left|$right",
            priority = 6,
          )
        }.onSuccess { queued++ }.onFailure { failed++ }
      }
    }
    val status = if (queued == 0) DedupClusterVerificationStatus.NO_ELIGIBLE_PAIR else DedupClusterVerificationStatus.QUEUED
    return DedupClusterVerificationResult(request.clusterId, status, memberIds.size, pairs.size, queued, skipped, failed)
  }

  private fun enqueueClusterRebuild(libraryId: String) {
    dedupRepository.enqueueWork(TsidCreator.getTsid256().toString(), libraryId, DedupWorkType.REBUILD_CLUSTERS, priority = -1)
  }

  private fun process(work: DedupWork) {
    val leaseToken = requireNotNull(work.leaseToken)
    try {
      when (work.type) {
        DedupWorkType.RECONCILE_EXACT_DUPLICATES -> {
          exactDuplicateLifecycle.reconcileLibrary(work.libraryId)
          enqueueClusterRebuild(work.libraryId)
          coverLifecycle.findDirtyBookIds(work.libraryId).forEach { bookId ->
            dedupRepository.enqueueWork(TsidCreator.getTsid256().toString(), work.libraryId, DedupWorkType.COMPUTE_COVER, bookId, priority = 2)
          }
          dedupRepository.enqueueWork(TsidCreator.getTsid256().toString(), work.libraryId, DedupWorkType.FIND_COVER_NEIGHBORS)
        }
        DedupWorkType.COMPUTE_COVER -> coverLifecycle.computeCover(work.targetKey)
        DedupWorkType.FIND_COVER_NEIGHBORS -> {
          coverLifecycle.rebuildCandidates(work.libraryId)
          enqueueClusterRebuild(work.libraryId)
        }
        DedupWorkType.VERIFY_RELATION -> {
          val ids = work.targetKey.split('|')
          require(ids.size == 2 && ids[0] < ids[1]) { "Invalid relation work target" }
          deepVerificationLifecycle.verifyRelation(ids[0], ids[1])
          enqueueClusterRebuild(work.libraryId)
        }
        DedupWorkType.REBUILD_CLUSTERS -> clusterLifecycle.rebuildLibrary(work.libraryId)
      }
      check(dedupRepository.completeWork(work.id, leaseToken, work.desiredRevision)) { "Dedup work lease changed before completion" }
    } catch (exception: Exception) {
      logger.error(exception) { "Dedup work ${work.id} (${work.type}) failed" }
      dedupRepository.failWork(
        work.id,
        leaseToken,
        work.desiredRevision,
        exception.javaClass.simpleName.uppercase(),
        exception.message?.replace(Regex("[\\r\\n]+"), " ") ?: exception.javaClass.simpleName,
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
