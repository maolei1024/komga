package org.gotson.komga.domain.service

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.DedupReviewCase
import org.gotson.komga.domain.model.DedupReviewCaseOrigin
import org.gotson.komga.domain.model.DedupReviewCaseStatus
import org.gotson.komga.domain.model.DedupWork
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.infrastructure.gorse.GorseDesiredStateLifecycle
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DedupWorkLifecycleTest {
  private val dedupRepository = mockk<DedupRepository>()
  private val taskEmitter = mockk<TaskEmitter>(relaxed = true)
  private val lifecycle =
    DedupWorkLifecycle(
      dedupRepository = dedupRepository,
      exactDuplicateLifecycle = mockk(),
      coverLifecycle = mockk(),
      deepVerificationLifecycle = mockk(),
      decisionLifecycle = mockk(),
      taskEmitter = taskEmitter,
      gorseDesiredStateLifecycle = mockk<GorseDesiredStateLifecycle>(),
    )

  @BeforeEach
  fun clearMockCalls() {
    clearMocks(dedupRepository, taskEmitter)
  }

  @Test
  fun `bulk verification reports each result and wakes each queued library once`() {
    val cases =
      mapOf(
        "cover-a" to reviewCase("cover-a", "library-a", 3),
        "cover-b" to reviewCase("cover-b", "library-a", 5),
        "cover-c" to reviewCase("cover-c", "library-b", 1),
        "exact" to reviewCase("exact", "library-a", 2, DedupReviewCaseOrigin.EXACT_FILE),
        "unsupported" to reviewCase("unsupported", "library-a", 1, members = setOf("a", "b", "c")),
      )
    every { dedupRepository.findReviewCase(any()) } answers { cases[firstArg()] }
    every {
      dedupRepository.enqueueWork(any(), any(), DedupWorkType.VERIFY_RELATION, any(), any(), 6, any())
    } answers {
      work(libraryId = secondArg(), caseId = arg(3))
    }

    val results =
      lifecycle.requestCaseVerifications(
        listOf(
          DedupCaseVerificationRequest("cover-a", 3),
          DedupCaseVerificationRequest("cover-b", 5),
          DedupCaseVerificationRequest("cover-c", 1),
          DedupCaseVerificationRequest("exact", 2),
          DedupCaseVerificationRequest("cover-a", 2),
          DedupCaseVerificationRequest("missing", 1),
          DedupCaseVerificationRequest("unsupported", 1),
        ),
      )

    assertThat(results.map { it.status })
      .containsExactly(
        DedupCaseVerificationStatus.QUEUED,
        DedupCaseVerificationStatus.QUEUED,
        DedupCaseVerificationStatus.QUEUED,
        DedupCaseVerificationStatus.SKIPPED_EXACT_FILE,
        DedupCaseVerificationStatus.STALE,
        DedupCaseVerificationStatus.NOT_FOUND,
        DedupCaseVerificationStatus.UNSUPPORTED_CASE,
      )
    verify(exactly = 1) { taskEmitter.drainDedupQueue("library-a", 6) }
    verify(exactly = 1) { taskEmitter.drainDedupQueue("library-b", 6) }
    verify(exactly = 3) {
      dedupRepository.enqueueWork(any(), any(), DedupWorkType.VERIFY_RELATION, any(), any(), 6, any())
    }
  }

  @Test
  fun `repeated bulk requests upsert the same natural work key`() {
    val reviewCase = reviewCase("cover-a", "library-a", 3)
    every { dedupRepository.findReviewCase("cover-a") } returns reviewCase
    every {
      dedupRepository.enqueueWork(any(), "library-a", DedupWorkType.VERIFY_RELATION, "cover-a", any(), 6, any())
    } returns work("library-a", "cover-a")

    repeat(2) {
      assertThat(lifecycle.requestCaseVerifications(listOf(DedupCaseVerificationRequest("cover-a", 3))).single().status)
        .isEqualTo(DedupCaseVerificationStatus.QUEUED)
    }

    verify(exactly = 2) {
      dedupRepository.enqueueWork(any(), "library-a", DedupWorkType.VERIFY_RELATION, "cover-a", any(), 6, any())
    }
  }

  private fun reviewCase(
    id: String,
    libraryId: String,
    revision: Long,
    origin: DedupReviewCaseOrigin = DedupReviewCaseOrigin.COVER_SIMILARITY,
    members: Set<String> = setOf("left", "right"),
  ) = DedupReviewCase(
    id = id,
    libraryId = libraryId,
    revision = revision,
    status = DedupReviewCaseStatus.REVIEW_REQUIRED,
    suggestedKeeperBookId = null,
    origin = origin,
    memberBookIds = members,
    createdDate = LocalDateTime.now(),
    lastModifiedDate = LocalDateTime.now(),
  )

  private fun work(
    libraryId: String,
    caseId: String,
  ): DedupWork {
    val now = LocalDateTime.now()
    return DedupWork(
      id = "work-$caseId",
      libraryId = libraryId,
      type = DedupWorkType.VERIFY_RELATION,
      targetKey = caseId,
      state = DedupWorkState.WAITING,
      desiredRevision = 1,
      completedRevision = 0,
      notBefore = now,
      nextRetryAt = null,
      leaseOwner = null,
      leaseToken = null,
      leaseUntil = null,
      attemptCount = 0,
      maxAttempts = 8,
      lastErrorCode = null,
      lastError = null,
      priority = 6,
      createdDate = now,
      lastModifiedDate = now,
      completedDate = null,
    )
  }
}
