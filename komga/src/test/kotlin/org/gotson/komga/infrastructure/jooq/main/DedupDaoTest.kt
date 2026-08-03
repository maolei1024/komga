package org.gotson.komga.infrastructure.jooq.main

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupDecision
import org.gotson.komga.domain.model.DedupDecisionItem
import org.gotson.komga.domain.model.DedupDecisionItemState
import org.gotson.komga.domain.model.DedupDecisionMode
import org.gotson.komga.domain.model.DedupDecisionState
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.model.makeLibrary
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.Duration
import java.time.LocalDateTime

@SpringBootTest
class DedupDaoTest(
  @Autowired private val dedupDao: DedupDao,
  @Autowired private val libraryDao: LibraryDao,
) {
  private val library = makeLibrary("dedup-test")

  @BeforeEach
  fun setup() {
    libraryDao.insert(library)
  }

  @AfterEach
  fun cleanup() {
    dedupDao.deleteAllDedupData()
    libraryDao.deleteAll()
  }

  @Test
  fun `library settings round trip through the main database`() {
    val value =
      DedupLibrarySettings(
        libraryId = library.id,
        enabled = true,
        paused = true,
        batchSize = 25,
        maxDurationSeconds = 45,
        quietPeriodSeconds = 120,
        completionStabilitySeconds = 240,
      )

    dedupDao.saveLibrarySettings(value)

    assertThat(dedupDao.findLibrarySettings(library.id))
      .usingRecursiveComparison()
      .ignoringFields("createdDate", "lastModifiedDate")
      .isEqualTo(value)
  }

  @Test
  fun `a dirty revision arriving during a lease remains pending after the old revision completes`() {
    val now = LocalDateTime.of(2026, 8, 3, 12, 0)
    val initial =
      dedupDao.enqueueWork(
        id = "work-1",
        libraryId = library.id,
        type = DedupWorkType.RECONCILE_EXACT_DUPLICATES,
        notBefore = now,
      )
    assertThat(initial.desiredRevision).isEqualTo(1)

    val claimed = dedupDao.claimNextWork("worker-1", Duration.ofMinutes(5), now = now)!!
    assertThat(claimed.state).isEqualTo(DedupWorkState.RUNNING)

    val dirtied =
      dedupDao.enqueueWork(
        id = "ignored-because-natural-key-is-unique",
        libraryId = library.id,
        type = DedupWorkType.RECONCILE_EXACT_DUPLICATES,
        notBefore = now,
      )
    assertThat(dirtied.desiredRevision).isEqualTo(2)
    assertThat(dirtied.leaseToken).isEqualTo(claimed.leaseToken)

    assertThat(dedupDao.completeWork(claimed.id, claimed.leaseToken!!, claimed.desiredRevision, now)).isTrue
    assertThat(dedupDao.findWorkById(claimed.id))
      .extracting("state", "desiredRevision", "completedRevision")
      .containsExactly(DedupWorkState.PENDING, 2L, 1L)

    val second = dedupDao.claimNextWork("worker-2", Duration.ofMinutes(5), now = now)!!
    assertThat(dedupDao.completeWork(second.id, second.leaseToken!!, second.desiredRevision, now)).isTrue
    assertThat(dedupDao.findWorkById(second.id))
      .extracting("state", "desiredRevision", "completedRevision")
      .containsExactly(DedupWorkState.SUCCEEDED, 2L, 2L)
  }

  @Test
  fun `ordinary failures back off and eventually require review`() {
    val now = LocalDateTime.of(2026, 8, 3, 12, 0)
    dedupDao.enqueueWork(
      id = "work-1",
      libraryId = library.id,
      type = DedupWorkType.RECONCILE_EXACT_DUPLICATES,
      notBefore = now,
      maxAttempts = 2,
    )

    val first = dedupDao.claimNextWork("worker-1", Duration.ofMinutes(5), now = now)!!
    assertThat(dedupDao.failWork(first.id, first.leaseToken!!, first.desiredRevision, "TEST", "failure", now)).isTrue
    val afterFirst = dedupDao.findWorkById(first.id)!!
    assertThat(afterFirst.state).isEqualTo(DedupWorkState.WAITING)
    assertThat(afterFirst.attemptCount).isEqualTo(1)
    assertThat(afterFirst.nextRetryAt).isAfter(now)

    val retryTime = afterFirst.nextRetryAt!!
    val second = dedupDao.claimNextWork("worker-2", Duration.ofMinutes(5), now = retryTime)!!
    assertThat(dedupDao.failWork(second.id, second.leaseToken!!, second.desiredRevision, "TEST", "failure", retryTime)).isTrue
    assertThat(dedupDao.findWorkById(second.id))
      .extracting("state", "attemptCount", "nextRetryAt")
      .containsExactly(DedupWorkState.FAILED_REVIEW, 2, null)
  }

  @Test
  fun `expired leases are reclaimed for a later worker`() {
    val now = LocalDateTime.of(2026, 8, 3, 12, 0)
    dedupDao.enqueueWork(
      id = "work-1",
      libraryId = library.id,
      type = DedupWorkType.RECONCILE_EXACT_DUPLICATES,
      notBefore = now,
    )
    dedupDao.claimNextWork("dead-worker", Duration.ofMinutes(1), now = now)

    assertThat(dedupDao.releaseExpiredLeases(now.plusMinutes(2))).isEqualTo(1)

    val reclaimed = dedupDao.claimNextWork("new-worker", Duration.ofMinutes(1), now = now.plusMinutes(2))
    assertThat(reclaimed).isNotNull
    assertThat(reclaimed!!.leaseOwner).isEqualTo("new-worker")
  }

  @Test
  fun `decision claim token serializes a per Book saga without mutating immutable plan snapshots`() {
    val now = LocalDateTime.of(2026, 8, 3, 12, 0)
    val decision =
      DedupDecision(
        id = "decision-1",
        reviewCaseId = null,
        planRevision = "plan-v1",
        mode = DedupDecisionMode.MANUAL,
        keeperBookId = "keeper",
        keeperSnapshotJson = "{\"hash\":\"keeper-hash\"}",
        planJson = "{\"remove\":[\"loser\"]}",
        evidenceJson = "{}",
        eligibilityJson = "{}",
        classifierRuleVersion = 3,
        manualConfirmationJson = "{\"accepted\":true}",
        state = DedupDecisionState.APPROVED,
        actorId = "admin",
        approvedDate = now,
        executedDate = null,
        completedDate = null,
        createdDate = now,
        lastModifiedDate = now,
      )
    val item =
      DedupDecisionItem(
        id = "item-1",
        decisionId = decision.id,
        bookId = "loser",
        seriesId = "series",
        libraryId = library.id,
        titleSnapshot = "Loser title",
        pathSnapshot = "/tmp/loser.cbz",
        expectedPath = "/tmp/loser.cbz",
        expectedSize = 100,
        expectedMtime = now,
        expectedArchiveHash = "archive-hash",
        sourceContentGeneration = "content-v1",
        seriesScopeRevision = "scope-v1",
        stateRevision = "state-v1",
        acknowledgedReasonsJson = "[]",
        directRelationId = "relation-1",
        directRelationGenerations = "relation-v1",
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
    dedupDao.insertDecision(decision, listOf(item))

    assertThat(
      dedupDao.claimDecision(
        decision.id,
        setOf(DedupDecisionState.APPROVED),
        DedupDecisionState.REVALIDATING,
        "executor-1",
        now.plusMinutes(5),
        now,
      ),
    ).isTrue
    assertThat(
      dedupDao.claimDecision(
        decision.id,
        setOf(DedupDecisionState.REVALIDATING),
        DedupDecisionState.REVALIDATING,
        "executor-2",
        now.plusMinutes(5),
        now,
      ),
    ).isFalse
    assertThat(
      dedupDao.updateDecisionItem(
        item.id,
        decision.id,
        "wrong-token",
        setOf(DedupDecisionItemState.PENDING),
        DedupDecisionItemState.REVALIDATING,
      ),
    ).isFalse
    assertThat(
      dedupDao.updateDecisionItem(
        item.id,
        decision.id,
        "executor-1",
        setOf(DedupDecisionItemState.PENDING),
        DedupDecisionItemState.REVALIDATING,
        incrementAttempt = true,
      ),
    ).isTrue

    assertThat(dedupDao.findDecision(decision.id))
      .extracting("planRevision", "keeperSnapshotJson", "planJson")
      .containsExactly("plan-v1", "{\"hash\":\"keeper-hash\"}", "{\"remove\":[\"loser\"]}")
    assertThat(dedupDao.findDecisionItem(item.id))
      .extracting("state", "attemptCount", "stateRevision")
      .containsExactly(DedupDecisionItemState.REVALIDATING, 1, "state-v1")
  }

  @Test
  fun `Gorse desired state is durable coalesced and retryable independently`() {
    val now = LocalDateTime.of(2026, 8, 3, 12, 0)
    dedupDao.enqueueGorseSync("series-1", library.id, desiredHidden = true, now = now)
    dedupDao.enqueueGorseSync("series-1", library.id, desiredHidden = false, now = now.plusSeconds(1))

    val claimed = dedupDao.findPendingGorseSync(now.plusSeconds(1))!!
    assertThat(claimed.desiredHidden).isFalse
    assertThat(claimed.state).isEqualTo("RUNNING")
    assertThat(dedupDao.failGorseSync("series-1", "temporary failure", now.plusSeconds(1))).isTrue
    val failed = dedupDao.findGorseSync("series-1")!!
    assertThat(failed.state).isEqualTo("PENDING")
    assertThat(failed.nextRetryAt).isAfter(now.plusSeconds(1))
    assertThat(dedupDao.findPendingGorseSync(now.plusSeconds(2))).isNull()

    val retryAt = failed.nextRetryAt!!
    assertThat(dedupDao.findPendingGorseSync(retryAt)).isNotNull
    assertThat(dedupDao.completeGorseSync("series-1", retryAt)).isTrue
    assertThat(dedupDao.findGorseSync("series-1")?.state).isEqualTo("SUCCEEDED")
  }
}
