package org.gotson.komga.domain.service

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

const val DEDUP_AUTO_RESOLUTION_ACTOR = "system:dedup-auto"

data class DedupAutoResolutionBatchResult(
  val attempts: Int,
  val submitted: Int,
  val continuationRequired: Boolean,
)

@Service
class DedupAutoResolutionLifecycle(
  private val dedupRepository: DedupRepository,
  private val resolutionRepository: DedupResolutionRepository,
  private val suggestionPlanner: DedupSuggestionPlanner,
  private val resolutionLifecycle: DedupResolutionLifecycle,
) {
  companion object {
    const val MAX_ATTEMPTS_PER_BATCH = 20
    private const val PAGE_SIZE = 100
  }

  fun submitBatch(libraryId: String): DedupAutoResolutionBatchResult {
    val settings = dedupRepository.findLibrarySettings(libraryId)
    if (settings == null || !settings.enabled || settings.paused || !settings.autoResolveSuggestions) {
      return DedupAutoResolutionBatchResult(0, 0, false)
    }

    var offset = 0
    var attempts = 0
    var submitted = 0
    while (attempts < MAX_ATTEMPTS_PER_BATCH) {
      val candidates = dedupRepository.findUnresolvedClusters(libraryId, offset, PAGE_SIZE)
      if (candidates.isEmpty()) break

      var retainedAtFront = 0
      candidates.forEach { candidate ->
        if (attempts >= MAX_ATTEMPTS_PER_BATCH) return@forEach
        val cluster = candidate.cluster
        if (
          cluster.status != DedupClusterStatus.UNPROCESSED ||
          resolutionRepository.hasResolutionAttempt(
            cluster.id,
            cluster.revision,
            DedupResolutionMode.SUGGESTED,
            DEDUP_AUTO_RESOLUTION_ACTOR,
          ) ||
          suggestionPlanner.evaluate(candidate).plan == null
        ) {
          retainedAtFront++
          return@forEach
        }

        try {
          resolutionLifecycle.createSuggested(cluster.id, cluster.revision, DEDUP_AUTO_RESOLUTION_ACTOR)
          attempts++
          submitted++
        } catch (exception: DedupResolutionValidationException) {
          retainedAtFront++
          logger.debug { "Skipping automatic Dedup resolution for cluster ${cluster.id}: ${exception.code}" }
        } catch (exception: DedupResolutionExecutionException) {
          if (exception.resolutionId == null) throw exception
          attempts++
          retainedAtFront++
          logger.warn { "Automatic Dedup resolution ${exception.resolutionId} for cluster ${cluster.id} needs attention: ${exception.code}" }
        }
      }

      offset += retainedAtFront
      if (candidates.size < PAGE_SIZE || attempts >= MAX_ATTEMPTS_PER_BATCH) break
    }

    return DedupAutoResolutionBatchResult(
      attempts = attempts,
      submitted = submitted,
      continuationRequired = attempts >= MAX_ATTEMPTS_PER_BATCH,
    )
  }
}
