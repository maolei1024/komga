package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.DedupDecision
import org.gotson.komga.domain.model.DedupDecisionItem
import org.gotson.komga.domain.model.DedupDecisionItemState
import org.gotson.komga.domain.model.DedupDecisionState
import org.gotson.komga.domain.model.DedupGorseSync
import java.time.LocalDateTime

interface DedupDecisionRepository {
  fun insertDecision(
    decision: DedupDecision,
    items: Collection<DedupDecisionItem>,
  )

  fun findDecision(decisionId: String): DedupDecision?

  fun findAllDecisions(): List<DedupDecision>

  fun findDecisionItems(decisionId: String): List<DedupDecisionItem>

  fun findDecisionItem(itemId: String): DedupDecisionItem?

  fun findDecisionsByStates(states: Set<DedupDecisionState>): List<DedupDecision>

  fun hasActiveDecisionForBooks(bookIds: Set<String>): Boolean

  fun countDecisionStates(): Map<DedupDecisionState, Int>

  fun countDecisionItemStates(): Map<DedupDecisionItemState, Int>

  fun countGorseSyncStates(): Map<String, Int>

  fun claimDecision(
    decisionId: String,
    expectedStates: Set<DedupDecisionState>,
    newState: DedupDecisionState,
    executionToken: String,
    leaseUntil: LocalDateTime,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun updateDecisionState(
    decisionId: String,
    executionToken: String,
    expectedStates: Set<DedupDecisionState>,
    newState: DedupDecisionState,
    resultJson: String = "{}",
    releaseLease: Boolean = false,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun updateDecisionItem(
    itemId: String,
    decisionId: String,
    executionToken: String,
    expectedStates: Set<DedupDecisionItemState>,
    newState: DedupDecisionItemState,
    resultCode: String? = null,
    resultJson: String? = null,
    lastError: String? = null,
    stabilityNotBefore: LocalDateTime? = null,
    deletedDate: LocalDateTime? = null,
    incrementAttempt: Boolean = false,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun releaseExpiredDecisionLeases(now: LocalDateTime = LocalDateTime.now()): Int

  fun enqueueGorseSync(
    seriesId: String,
    libraryId: String,
    desiredHidden: Boolean,
    now: LocalDateTime = LocalDateTime.now(),
  )

  fun findPendingGorseSync(now: LocalDateTime = LocalDateTime.now()): DedupGorseSync?

  fun findGorseSync(seriesId: String): DedupGorseSync?

  fun completeGorseSync(
    seriesId: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun failGorseSync(
    seriesId: String,
    error: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean
}
