package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupOverride
import org.gotson.komga.domain.model.DedupPageFeature
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupReviewCase
import org.gotson.komga.domain.model.DedupReviewCaseCandidate
import org.gotson.komga.domain.model.DedupWork
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import java.time.Duration
import java.time.LocalDateTime

interface DedupRepository {
  fun findLibrarySettings(libraryId: String): DedupLibrarySettings?

  fun findAllLibrarySettings(): List<DedupLibrarySettings>

  fun saveLibrarySettings(settings: DedupLibrarySettings)

  fun enqueueWork(
    id: String,
    libraryId: String,
    type: DedupWorkType,
    targetKey: String = "",
    notBefore: LocalDateTime = LocalDateTime.now(),
    priority: Int = 0,
    maxAttempts: Int = 8,
  ): DedupWork

  fun claimNextWork(
    owner: String,
    leaseDuration: Duration,
    libraryId: String? = null,
    allowedTypes: Set<DedupWorkType>? = null,
    now: LocalDateTime = LocalDateTime.now(),
  ): DedupWork?

  fun completeWork(
    workId: String,
    leaseToken: String,
    completedRevision: Long,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun failWork(
    workId: String,
    leaseToken: String,
    attemptedRevision: Long,
    errorCode: String,
    sanitizedError: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun retryFailedWork(
    workId: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun releaseExpiredLeases(now: LocalDateTime = LocalDateTime.now()): Int

  fun findWorkById(workId: String): DedupWork?

  fun findAllWork(): List<DedupWork>

  fun countWorkByState(): Map<DedupWorkState, Int>

  fun findFeature(bookId: String): DedupFeature?

  fun findReadyCoverFeatures(libraryId: String): List<DedupFeature>

  fun saveFeature(feature: DedupFeature)

  fun deleteFeaturesNotIn(
    libraryId: String,
    activeBookIds: Set<String>,
  ): Int

  fun findPageFeatures(
    bookId: String,
    sourceContentGeneration: String,
    featureSchemaVersion: Int,
  ): List<DedupPageFeature>

  fun replacePageFeatures(
    bookId: String,
    sourceContentGeneration: String,
    featureSchemaVersion: Int,
    features: Collection<DedupPageFeature>,
  )

  fun replaceReviewCases(
    libraryId: String,
    origin: org.gotson.komga.domain.model.DedupReviewCaseOrigin,
    candidates: Collection<DedupReviewCaseCandidate>,
    now: LocalDateTime = LocalDateTime.now(),
  )

  fun saveReviewCase(
    candidate: DedupReviewCaseCandidate,
    now: LocalDateTime = LocalDateTime.now(),
  )

  fun findReviewCase(caseId: String): DedupReviewCase?

  fun findRelation(
    firstBookId: String,
    secondBookId: String,
  ): DedupRelation?

  fun setReviewCaseKeeper(
    caseId: String,
    expectedRevision: Long,
    bookId: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun applyOverride(
    caseId: String,
    expectedRevision: Long,
    override: DedupOverride,
    newStatus: org.gotson.komga.domain.model.DedupReviewCaseStatus,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun findProtectedBookIds(bookIds: Set<String>): Set<String>

  fun findReviewCases(
    libraryId: String? = null,
    origin: org.gotson.komga.domain.model.DedupReviewCaseOrigin? = null,
  ): List<DedupReviewCase>

  fun deleteAllDedupData()
}
