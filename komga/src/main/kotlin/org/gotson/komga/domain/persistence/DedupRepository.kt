package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupEvidenceMaturity
import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupGorseSync
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupPageFeature
import org.gotson.komga.domain.model.DedupPairDecision
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupWork
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import java.time.Duration
import java.time.LocalDateTime

interface DedupRepository {
  fun findLibrarySettings(libraryId: String): DedupLibrarySettings?

  fun findAllLibrarySettings(): List<DedupLibrarySettings>

  fun saveLibrarySettings(settings: DedupLibrarySettings)

  fun updateLibraryBatchResult(
    libraryId: String,
    processedBookCount: Int,
    completedDate: LocalDateTime = LocalDateTime.now(),
  )

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

  fun countPendingWork(type: DedupWorkType): Int

  /** Count distinct Books that still need a scan, whether discovered lazily or already queued. */
  fun countPendingScanBooks(
    libraryIds: Set<String>,
    featureSchemaVersion: Int,
  ): Int

  /** Stable DAO-level LIMIT query for active CBZ Books without the current feature schema. */
  fun findUnscannedBookIds(
    libraryId: String,
    featureSchemaVersion: Int,
    limit: Int,
  ): List<String>

  fun findFeature(bookId: String): DedupFeature?

  fun findFeatures(bookIds: Set<String>): List<DedupFeature>

  fun findReadyCoverFeatures(libraryId: String): List<DedupFeature>

  fun findFeaturesByLibrary(libraryId: String): List<DedupFeature>

  fun saveFeature(feature: DedupFeature)

  fun deleteBookData(bookId: String)

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

  fun findRelation(
    firstBookId: String,
    secondBookId: String,
  ): DedupRelation?

  fun findRelations(libraryId: String): List<DedupRelation>

  fun findRelationsForBooks(bookIds: Set<String>): List<DedupRelation>

  fun findRelationsTouchingBooks(
    libraryId: String,
    bookIds: Set<String>,
  ): List<DedupRelation>

  fun saveRelation(relation: DedupRelation)

  fun replaceExactRelationsForBook(
    bookId: String,
    relations: Collection<DedupRelation>,
  )

  fun replaceCoverRelationsForBook(
    bookId: String,
    relations: Collection<DedupRelation>,
    now: LocalDateTime = LocalDateTime.now(),
  )

  fun findPairDecisions(libraryId: String): List<DedupPairDecision>

  fun savePairDecisions(decisions: Collection<DedupPairDecision>)

  fun findCluster(clusterId: String): DedupClusterWithMembers?

  fun findAllClusters(libraryId: String? = null): List<DedupClusterWithMembers>

  fun findClusters(
    libraryId: String? = null,
    status: DedupClusterStatus? = null,
    reviewable: Boolean? = null,
    evidenceMaturity: DedupEvidenceMaturity? = null,
    offset: Int = 0,
    limit: Int = 20,
  ): List<DedupClusterWithMembers>

  fun countClusters(
    libraryId: String? = null,
    status: DedupClusterStatus? = null,
    reviewable: Boolean? = null,
    evidenceMaturity: DedupEvidenceMaturity? = null,
  ): Long

  fun countClustersByStatus(): Map<DedupClusterStatus, Int>

  fun findUnresolvedClusters(
    libraryId: String? = null,
    offset: Int = 0,
    limit: Int = 20,
  ): List<DedupClusterWithMembers>

  fun countUnresolvedClusters(libraryId: String? = null): Long

  fun lockLibraryForClusterRebuild(libraryId: String)

  fun saveCluster(
    cluster: DedupCluster,
    members: Collection<DedupClusterMember>,
  )

  fun markClusterSuperseded(
    clusterId: String,
    supersededBy: String,
    now: LocalDateTime = LocalDateTime.now(),
  )

  fun claimCluster(
    clusterId: String,
    expectedRevision: Long,
    stateFingerprint: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun updateClusterState(
    clusterId: String,
    expectedStatuses: Set<DedupClusterStatus>,
    newStatus: DedupClusterStatus,
    lastResolutionId: String? = null,
    reopenReason: String? = null,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun enqueueGorseSync(
    seriesId: String,
    libraryId: String,
    desiredHidden: Boolean,
    now: LocalDateTime = LocalDateTime.now(),
  ): DedupGorseSync

  fun findPendingGorseSync(now: LocalDateTime = LocalDateTime.now()): DedupGorseSync?

  fun findGorseSync(seriesId: String): DedupGorseSync?

  fun completeGorseSync(
    seriesId: String,
    expectedHidden: Boolean,
    expectedRevision: Long,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun failGorseSync(
    seriesId: String,
    expectedHidden: Boolean,
    expectedRevision: Long,
    error: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Boolean

  fun countGorseSyncStates(): Map<String, Int>
}
