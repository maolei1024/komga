package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionMember
import org.gotson.komga.domain.model.DedupResolutionMemberState
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.model.DedupResolutionState
import java.time.LocalDateTime

interface DedupResolutionRepository {
  fun insertResolution(
    resolution: DedupResolution,
    members: Collection<DedupResolutionMember>,
  )

  fun findResolution(resolutionId: String): DedupResolution?

  fun findResolutions(
    offset: Int = 0,
    limit: Int = 20,
  ): List<DedupResolution>

  fun countResolutions(): Long

  fun findProcessedResolutions(
    offset: Int = 0,
    limit: Int = 20,
  ): List<DedupResolution>

  fun countProcessedResolutions(): Long

  fun countResolutionsByState(): Map<DedupResolutionState, Int>

  fun findResolutionMembers(resolutionId: String): List<DedupResolutionMember>

  fun hasActiveResolutionForBooks(bookIds: Set<String>): Boolean

  fun hasResolutionAttempt(
    clusterId: String,
    clusterRevision: Long,
    mode: DedupResolutionMode,
    actorId: String,
  ): Boolean

  fun updateResolution(
    resolutionId: String,
    expectedStates: Set<DedupResolutionState>,
    state: DedupResolutionState,
    resultJson: String,
    completedDate: LocalDateTime? = null,
    leaseToken: String? = null,
    leaseUntil: LocalDateTime? = null,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun updateResolutionMember(
    resolutionId: String,
    bookId: String,
    expectedStates: Set<DedupResolutionMemberState>,
    state: DedupResolutionMemberState,
    expectedPath: String? = null,
    expectedSize: Long? = null,
    expectedArchiveHash: String? = null,
    resultCode: String? = null,
    resultJson: String? = null,
    lastError: String? = null,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun releaseExpiredResolutionLeases(now: LocalDateTime = LocalDateTime.now()): Int
}
