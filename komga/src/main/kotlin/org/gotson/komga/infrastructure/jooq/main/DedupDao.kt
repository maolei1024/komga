package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupEvidenceMaturity
import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupFeatureState
import org.gotson.komga.domain.model.DedupGorseSync
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupPageFeature
import org.gotson.komga.domain.model.DedupPairDecision
import org.gotson.komga.domain.model.DedupPairDecisionType
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationStatus
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionMember
import org.gotson.komga.domain.model.DedupResolutionMemberState
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.model.DedupWork
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.gotson.komga.jooq.main.tables.records.DedupFeatureRecord
import org.gotson.komga.jooq.main.tables.records.DedupLibrarySettingsRecord
import org.gotson.komga.jooq.main.tables.records.DedupWorkRecord
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.min

@Component
class DedupDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  DedupRepository,
  DedupResolutionRepository {
  private val settings = Tables.DEDUP_LIBRARY_SETTINGS
  private val work = Tables.DEDUP_WORK
  private val feature = Tables.DEDUP_FEATURE
  private val pageFeature = Tables.DEDUP_PAGE_FEATURE
  private val relation = Tables.DEDUP_RELATION
  private val cluster = Tables.DEDUP_CLUSTER
  private val clusterMember = Tables.DEDUP_CLUSTER_MEMBER
  private val resolution = Tables.DEDUP_RESOLUTION
  private val resolutionMember = Tables.DEDUP_RESOLUTION_MEMBER
  private val pairDecision = Tables.DEDUP_PAIR_DECISION
  private val gorseSync = Tables.DEDUP_GORSE_SYNC
  private val book = Tables.BOOK

  override fun findLibrarySettings(libraryId: String): DedupLibrarySettings? =
    dslRO
      .selectFrom(settings)
      .where(settings.LIBRARY_ID.eq(libraryId))
      .fetchOne()
      ?.toDomain()

  override fun findAllLibrarySettings(): List<DedupLibrarySettings> =
    dslRO
      .selectFrom(settings)
      .orderBy(settings.LIBRARY_ID)
      .fetch()
      .map { it.toDomain() }

  override fun saveLibrarySettings(value: DedupLibrarySettings) {
    dslRW
      .insertInto(
        settings,
        settings.LIBRARY_ID,
        settings.ENABLED,
        settings.PAUSED,
        settings.SCAN_INTERVAL,
        settings.BATCH_SIZE,
        settings.MAX_DURATION_SECONDS,
        settings.QUIET_PERIOD_SECONDS,
        settings.COVER_CANDIDATE_DISTANCE,
        settings.COVER_TOP_K,
        settings.AUTO_RESOLVE_SUGGESTIONS,
        settings.CREATED_DATE,
        settings.LAST_MODIFIED_DATE,
        settings.LAST_BATCH_DATE,
        settings.LAST_BATCH_BOOK_COUNT,
      ).values(
        value.libraryId,
        value.enabled,
        value.paused,
        value.scanInterval.name,
        value.batchSize,
        value.maxDurationSeconds,
        value.quietPeriodSeconds,
        value.coverCandidateDistance,
        value.coverTopK,
        value.autoResolveSuggestions,
        value.createdDate,
        value.lastModifiedDate,
        value.lastBatchDate,
        value.lastBatchBookCount,
      ).onDuplicateKeyUpdate()
      .set(settings.ENABLED, value.enabled)
      .set(settings.PAUSED, value.paused)
      .set(settings.SCAN_INTERVAL, value.scanInterval.name)
      .set(settings.BATCH_SIZE, value.batchSize)
      .set(settings.MAX_DURATION_SECONDS, value.maxDurationSeconds)
      .set(settings.QUIET_PERIOD_SECONDS, value.quietPeriodSeconds)
      .set(settings.COVER_CANDIDATE_DISTANCE, value.coverCandidateDistance)
      .set(settings.COVER_TOP_K, value.coverTopK)
      .set(settings.AUTO_RESOLVE_SUGGESTIONS, value.autoResolveSuggestions)
      .set(settings.LAST_MODIFIED_DATE, value.lastModifiedDate)
      .set(settings.LAST_BATCH_DATE, value.lastBatchDate)
      .set(settings.LAST_BATCH_BOOK_COUNT, value.lastBatchBookCount)
      .execute()
  }

  override fun updateLibraryBatchResult(
    libraryId: String,
    processedBookCount: Int,
    completedDate: LocalDateTime,
  ) {
    require(processedBookCount >= 0)
    check(
      dslRW
        .update(settings)
        .set(settings.LAST_BATCH_DATE, completedDate)
        .set(settings.LAST_BATCH_BOOK_COUNT, processedBookCount)
        .set(settings.LAST_MODIFIED_DATE, completedDate)
        .where(settings.LIBRARY_ID.eq(libraryId))
        .execute() == 1,
    ) { "Dedup Library settings disappeared while recording a batch" }
  }

  override fun enqueueWork(
    id: String,
    libraryId: String,
    type: DedupWorkType,
    targetKey: String,
    notBefore: LocalDateTime,
    priority: Int,
    maxAttempts: Int,
  ): DedupWork {
    require(maxAttempts > 0)
    val now = LocalDateTime.now()
    dslRW
      .insertInto(
        work,
        work.ID,
        work.LIBRARY_ID,
        work.TYPE,
        work.TARGET_KEY,
        work.STATE,
        work.DESIRED_REVISION,
        work.COMPLETED_REVISION,
        work.NOT_BEFORE,
        work.ATTEMPT_COUNT,
        work.MAX_ATTEMPTS,
        work.PRIORITY,
        work.CREATED_DATE,
        work.LAST_MODIFIED_DATE,
      ).values(id, libraryId, type.name, targetKey, DedupWorkState.WAITING.name, 1L, 0L, notBefore, 0, maxAttempts, priority, now, now)
      .onDuplicateKeyUpdate()
      .set(work.DESIRED_REVISION, work.DESIRED_REVISION.plus(1L))
      .set(work.STATE, DSL.`when`(work.STATE.eq(DedupWorkState.RUNNING.name), DedupWorkState.RUNNING.name).otherwise(DedupWorkState.WAITING.name))
      .set(work.NOT_BEFORE, notBefore)
      .set(work.NEXT_RETRY_AT, null as LocalDateTime?)
      .set(work.ATTEMPT_COUNT, DSL.`when`(work.STATE.eq(DedupWorkState.RUNNING.name), work.ATTEMPT_COUNT).otherwise(0))
      .set(work.MAX_ATTEMPTS, maxAttempts)
      .set(work.PRIORITY, DSL.greatest(work.PRIORITY, DSL.inline(priority)))
      .set(work.COMPLETED_DATE, null as LocalDateTime?)
      .set(work.LAST_MODIFIED_DATE, now)
      .execute()
    return requireNotNull(findWorkByNaturalKey(libraryId, type, targetKey))
  }

  @Transactional
  override fun claimNextWork(
    owner: String,
    leaseDuration: Duration,
    libraryId: String?,
    allowedTypes: Set<DedupWorkType>?,
    now: LocalDateTime,
  ): DedupWork? {
    require(!leaseDuration.isNegative && !leaseDuration.isZero)
    repeat(3) {
      val record =
        dslRW
          .selectFrom(work)
          .where(work.STATE.`in`(DedupWorkState.WAITING.name, DedupWorkState.PENDING.name))
          .and(work.NOT_BEFORE.le(now))
          .and(work.NEXT_RETRY_AT.isNull.or(work.NEXT_RETRY_AT.le(now)))
          .and(work.LEASE_TOKEN.isNull)
          .and(libraryId?.let { work.LIBRARY_ID.eq(it) } ?: DSL.noCondition())
          .and(allowedTypes?.let { work.TYPE.`in`(it.map(DedupWorkType::name)) } ?: DSL.noCondition())
          .orderBy(work.PRIORITY.desc(), work.CREATED_DATE, work.ID)
          .limit(1)
          .fetchOne() ?: return null
      val token = UUID.randomUUID().toString()
      val updated =
        dslRW
          .update(work)
          .set(work.STATE, DedupWorkState.RUNNING.name)
          .set(work.LEASE_OWNER, owner)
          .set(work.LEASE_TOKEN, token)
          .set(work.LEASE_UNTIL, now.plus(leaseDuration))
          .set(work.LAST_MODIFIED_DATE, now)
          .where(work.ID.eq(record.id))
          .and(work.STATE.eq(record.state))
          .and(work.LEASE_TOKEN.isNull)
          .execute()
      if (updated == 1) return findWorkById(record.id!!)
    }
    return null
  }

  override fun completeWork(
    workId: String,
    leaseToken: String,
    completedRevision: Long,
    now: LocalDateTime,
  ): Boolean {
    val newer = work.DESIRED_REVISION.gt(completedRevision)
    val pending = DSL.`when`(work.NOT_BEFORE.gt(now), DedupWorkState.WAITING.name).otherwise(DedupWorkState.PENDING.name)
    return dslRW
      .update(work)
      .set(work.COMPLETED_REVISION, completedRevision)
      .set(work.STATE, DSL.`when`(newer, pending).otherwise(DedupWorkState.SUCCEEDED.name))
      .set(work.LEASE_OWNER, null as String?)
      .set(work.LEASE_TOKEN, null as String?)
      .set(work.LEASE_UNTIL, null as LocalDateTime?)
      .set(work.ATTEMPT_COUNT, 0)
      .set(work.NEXT_RETRY_AT, null as LocalDateTime?)
      .set(work.LAST_ERROR_CODE, null as String?)
      .set(work.LAST_ERROR, null as String?)
      .set(work.COMPLETED_DATE, DSL.`when`(newer, null as LocalDateTime?).otherwise(now))
      .set(work.LAST_MODIFIED_DATE, now)
      .where(work.ID.eq(workId))
      .and(work.STATE.eq(DedupWorkState.RUNNING.name))
      .and(work.LEASE_TOKEN.eq(leaseToken))
      .execute() == 1
  }

  @Transactional
  override fun failWork(
    workId: String,
    leaseToken: String,
    attemptedRevision: Long,
    errorCode: String,
    sanitizedError: String,
    now: LocalDateTime,
  ): Boolean {
    val current =
      dslRW
        .selectFrom(work)
        .where(work.ID.eq(workId))
        .and(work.STATE.eq(DedupWorkState.RUNNING.name))
        .and(work.LEASE_TOKEN.eq(leaseToken))
        .fetchOne() ?: return false
    val newer = current.desiredRevision!! > attemptedRevision
    val attempts = if (newer) 0 else current.attemptCount!! + 1
    val terminal = !newer && attempts >= current.maxAttempts!!
    val retryAt = if (newer || terminal) null else now.plusSeconds(min(3_600L, 5L * (1L shl min(attempts - 1, 10))))
    return dslRW
      .update(work)
      .set(work.STATE, if (terminal) DedupWorkState.FAILED_REVIEW.name else DedupWorkState.WAITING.name)
      .set(work.LEASE_OWNER, null as String?)
      .set(work.LEASE_TOKEN, null as String?)
      .set(work.LEASE_UNTIL, null as LocalDateTime?)
      .set(work.ATTEMPT_COUNT, attempts)
      .set(work.NEXT_RETRY_AT, retryAt)
      .set(work.LAST_ERROR_CODE, errorCode.take(100))
      .set(work.LAST_ERROR, sanitizedError.take(500))
      .set(work.LAST_MODIFIED_DATE, now)
      .where(work.ID.eq(workId))
      .and(work.STATE.eq(DedupWorkState.RUNNING.name))
      .and(work.LEASE_TOKEN.eq(leaseToken))
      .execute() == 1
  }

  override fun retryFailedWork(
    workId: String,
    now: LocalDateTime,
  ): Boolean =
    dslRW
      .update(work)
      .set(work.STATE, DedupWorkState.WAITING.name)
      .set(work.ATTEMPT_COUNT, 0)
      .set(work.NEXT_RETRY_AT, null as LocalDateTime?)
      .set(work.LAST_ERROR_CODE, null as String?)
      .set(work.LAST_ERROR, null as String?)
      .set(work.NOT_BEFORE, now)
      .set(work.LAST_MODIFIED_DATE, now)
      .where(work.ID.eq(workId))
      .and(work.STATE.eq(DedupWorkState.FAILED_REVIEW.name))
      .execute() == 1

  override fun releaseExpiredLeases(now: LocalDateTime): Int =
    dslRW
      .update(work)
      .set(work.STATE, DedupWorkState.WAITING.name)
      .set(work.LEASE_OWNER, null as String?)
      .set(work.LEASE_TOKEN, null as String?)
      .set(work.LEASE_UNTIL, null as LocalDateTime?)
      .set(work.NEXT_RETRY_AT, now)
      .set(work.LAST_ERROR_CODE, "LEASE_EXPIRED")
      .set(work.LAST_ERROR, "The previous worker lease expired before completion")
      .set(work.LAST_MODIFIED_DATE, now)
      .where(work.STATE.eq(DedupWorkState.RUNNING.name))
      .and(work.LEASE_UNTIL.lt(now))
      .execute()

  override fun findWorkById(workId: String): DedupWork? =
    dslRO
      .selectFrom(work)
      .where(work.ID.eq(workId))
      .fetchOne()
      ?.toDomain()

  private fun findWorkByNaturalKey(
    libraryId: String,
    type: DedupWorkType,
    targetKey: String,
  ): DedupWork? =
    dslRO
      .selectFrom(work)
      .where(work.LIBRARY_ID.eq(libraryId))
      .and(work.TYPE.eq(type.name))
      .and(work.TARGET_KEY.eq(targetKey))
      .fetchOne()
      ?.toDomain()

  override fun findAllWork(): List<DedupWork> =
    dslRO
      .selectFrom(work)
      .orderBy(work.CREATED_DATE, work.ID)
      .fetch()
      .map { it.toDomain() }

  override fun countWorkByState(): Map<DedupWorkState, Int> =
    dslRO
      .select(work.STATE, DSL.count())
      .from(work)
      .groupBy(work.STATE)
      .fetch()
      .associate { DedupWorkState.valueOf(it.value1()) to it.value2() }

  override fun countPendingWork(type: DedupWorkType): Int =
    dslRO
      .selectCount()
      .from(work)
      .where(work.TYPE.eq(type.name))
      .and(work.STATE.`in`(DedupWorkState.WAITING.name, DedupWorkState.PENDING.name, DedupWorkState.RUNNING.name, DedupWorkState.FAILED_REVIEW.name))
      .fetchOne(0, Int::class.java) ?: 0

  override fun countPendingScanBooks(
    libraryIds: Set<String>,
    featureSchemaVersion: Int,
  ): Int {
    if (libraryIds.isEmpty()) return 0
    val unscanned =
      dslRO
        .select(book.ID)
        .from(book)
        .leftJoin(feature)
        .on(feature.BOOK_ID.eq(book.ID))
        .where(book.LIBRARY_ID.`in`(libraryIds))
        .and(book.DELETED_DATE.isNull)
        .and(DSL.lower(book.URL).like("%.cbz"))
        .and(feature.BOOK_ID.isNull.or(feature.FEATURE_SCHEMA_VERSION.ne(featureSchemaVersion)))
    val queued =
      dslRO
        .select(work.TARGET_KEY)
        .from(work)
        .where(work.LIBRARY_ID.`in`(libraryIds))
        .and(work.TYPE.eq(DedupWorkType.SCAN_BOOK.name))
        .and(work.STATE.`in`(DedupWorkState.WAITING.name, DedupWorkState.PENDING.name, DedupWorkState.RUNNING.name, DedupWorkState.FAILED_REVIEW.name))
    return dslRO
      .selectCount()
      .from(unscanned.union(queued).asTable("pending_scan_books"))
      .fetchOne(0, Int::class.java) ?: 0
  }

  override fun findUnscannedBookIds(
    libraryId: String,
    featureSchemaVersion: Int,
    limit: Int,
  ): List<String> {
    require(limit > 0)
    return dslRO
      .select(book.ID)
      .from(book)
      .leftJoin(feature)
      .on(feature.BOOK_ID.eq(book.ID))
      .where(book.LIBRARY_ID.eq(libraryId))
      .and(book.DELETED_DATE.isNull)
      .and(DSL.lower(book.URL).like("%.cbz"))
      .and(feature.BOOK_ID.isNull.or(feature.FEATURE_SCHEMA_VERSION.ne(featureSchemaVersion)))
      .andNotExists(
        dslRO
          .selectOne()
          .from(work)
          .where(work.LIBRARY_ID.eq(libraryId))
          .and(work.TYPE.eq(DedupWorkType.SCAN_BOOK.name))
          .and(work.TARGET_KEY.eq(book.ID))
          .and(work.STATE.`in`(DedupWorkState.WAITING.name, DedupWorkState.PENDING.name, DedupWorkState.RUNNING.name)),
      ).orderBy(book.CREATED_DATE, book.ID)
      .limit(limit)
      .fetch(book.ID)
  }

  override fun findFeature(bookId: String): DedupFeature? =
    dslRO
      .selectFrom(feature)
      .where(feature.BOOK_ID.eq(bookId))
      .fetchOne()
      ?.toDomain()

  override fun findFeatures(bookIds: Set<String>): List<DedupFeature> {
    if (bookIds.isEmpty()) return emptyList()
    return dslRO
      .selectFrom(feature)
      .where(feature.BOOK_ID.`in`(bookIds))
      .orderBy(feature.BOOK_ID)
      .fetch()
      .map { it.toDomain() }
  }

  override fun findReadyCoverFeatures(libraryId: String): List<DedupFeature> =
    dslRO
      .selectFrom(feature)
      .where(feature.LIBRARY_ID.eq(libraryId))
      .and(feature.COVER_STATE.eq(DedupFeatureState.READY.name))
      .and(feature.COVER_HASH.isNotNull)
      .orderBy(feature.BOOK_ID)
      .fetch()
      .map { it.toDomain() }

  override fun findFeaturesByLibrary(libraryId: String): List<DedupFeature> =
    dslRO
      .selectFrom(feature)
      .where(feature.LIBRARY_ID.eq(libraryId))
      .orderBy(feature.BOOK_ID)
      .fetch()
      .map { it.toDomain() }

  override fun saveFeature(value: DedupFeature) {
    dslRW
      .insertInto(
        feature,
        feature.BOOK_ID,
        feature.SERIES_ID,
        feature.LIBRARY_ID,
        feature.SOURCE_CONTENT_GENERATION,
        feature.SOURCE_COVER_GENERATION,
        feature.SOURCE_METADATA_GENERATION,
        feature.SERIES_SCOPE_REVISION,
        feature.FEATURE_SCHEMA_VERSION,
        feature.COVER_STATE,
        feature.PAGE_STATE,
        feature.COVER_SOURCE,
        feature.COVER_HASH,
        feature.COVER_QUALITY,
        feature.PAGE_COUNT,
        feature.ANALYZED_DATE,
        feature.LAST_MODIFIED_DATE,
        feature.ARCHIVE_HASH,
        feature.ARCHIVE_HASH_PATH,
        feature.ARCHIVE_HASH_SIZE,
        feature.ARCHIVE_HASH_SCHEMA_VERSION,
        feature.ARCHIVE_HASH_DATE,
      ).values(
        value.bookId,
        value.seriesId,
        value.libraryId,
        value.sourceContentGeneration,
        value.sourceCoverGeneration,
        value.sourceMetadataGeneration,
        value.seriesScopeRevision,
        value.featureSchemaVersion,
        value.coverState.name,
        value.pageState.name,
        value.coverSource,
        value.coverHash,
        value.coverQuality,
        value.pageCount,
        value.analyzedDate,
        value.lastModifiedDate,
        value.archiveHash,
        value.archiveHashPath,
        value.archiveHashSize,
        value.archiveHashSchemaVersion,
        value.archiveHashDate,
      ).onDuplicateKeyUpdate()
      .set(feature.SERIES_ID, value.seriesId)
      .set(feature.LIBRARY_ID, value.libraryId)
      .set(feature.SOURCE_CONTENT_GENERATION, value.sourceContentGeneration)
      .set(feature.SOURCE_COVER_GENERATION, value.sourceCoverGeneration)
      .set(feature.SOURCE_METADATA_GENERATION, value.sourceMetadataGeneration)
      .set(feature.SERIES_SCOPE_REVISION, value.seriesScopeRevision)
      .set(feature.FEATURE_SCHEMA_VERSION, value.featureSchemaVersion)
      .set(feature.COVER_STATE, value.coverState.name)
      .set(feature.PAGE_STATE, value.pageState.name)
      .set(feature.COVER_SOURCE, value.coverSource)
      .set(feature.COVER_HASH, value.coverHash)
      .set(feature.COVER_QUALITY, value.coverQuality)
      .set(feature.PAGE_COUNT, value.pageCount)
      .set(feature.ANALYZED_DATE, value.analyzedDate)
      .set(feature.LAST_MODIFIED_DATE, value.lastModifiedDate)
      .set(feature.ARCHIVE_HASH, value.archiveHash)
      .set(feature.ARCHIVE_HASH_PATH, value.archiveHashPath)
      .set(feature.ARCHIVE_HASH_SIZE, value.archiveHashSize)
      .set(feature.ARCHIVE_HASH_SCHEMA_VERSION, value.archiveHashSchemaVersion)
      .set(feature.ARCHIVE_HASH_DATE, value.archiveHashDate)
      .execute()
  }

  @Transactional
  override fun deleteBookData(bookId: String) {
    dslRW.deleteFrom(relation).where(relation.BOOK_LOW_ID.eq(bookId).or(relation.BOOK_HIGH_ID.eq(bookId))).execute()
    dslRW.deleteFrom(pageFeature).where(pageFeature.BOOK_ID.eq(bookId)).execute()
    dslRW.deleteFrom(feature).where(feature.BOOK_ID.eq(bookId)).execute()
  }

  override fun findPageFeatures(
    bookId: String,
    sourceContentGeneration: String,
    featureSchemaVersion: Int,
  ): List<DedupPageFeature> =
    dslRO
      .selectFrom(pageFeature)
      .where(pageFeature.BOOK_ID.eq(bookId))
      .and(pageFeature.SOURCE_CONTENT_GENERATION.eq(sourceContentGeneration))
      .and(pageFeature.FEATURE_SCHEMA_VERSION.eq(featureSchemaVersion))
      .orderBy(pageFeature.PAGE_NUMBER)
      .fetch {
        DedupPageFeature(it.bookId!!, it.sourceContentGeneration!!, it.featureSchemaVersion!!, it.pageNumber!!, it.exactHash, it.perceptualHash, it.quality)
      }

  @Transactional
  override fun replacePageFeatures(
    bookId: String,
    sourceContentGeneration: String,
    featureSchemaVersion: Int,
    features: Collection<DedupPageFeature>,
  ) {
    dslRW.deleteFrom(pageFeature).where(pageFeature.BOOK_ID.eq(bookId)).execute()
    features.forEach { value ->
      dslRW
        .insertInto(
          pageFeature,
          pageFeature.BOOK_ID,
          pageFeature.SOURCE_CONTENT_GENERATION,
          pageFeature.FEATURE_SCHEMA_VERSION,
          pageFeature.PAGE_NUMBER,
          pageFeature.EXACT_HASH,
          pageFeature.PERCEPTUAL_HASH,
          pageFeature.QUALITY,
        ).values(bookId, sourceContentGeneration, featureSchemaVersion, value.pageNumber, value.exactHash, value.perceptualHash, value.quality)
        .execute()
    }
  }

  override fun findRelation(
    firstBookId: String,
    secondBookId: String,
  ): DedupRelation? {
    val (low, high) = canonicalPair(firstBookId, secondBookId)
    return dslRO
      .selectFrom(relation)
      .where(relation.BOOK_LOW_ID.eq(low))
      .and(relation.BOOK_HIGH_ID.eq(high))
      .fetchOne()
      ?.toRelation()
  }

  override fun findRelations(libraryId: String): List<DedupRelation> =
    dslRO
      .selectFrom(relation)
      .where(relation.LIBRARY_ID.eq(libraryId))
      .orderBy(relation.BOOK_LOW_ID, relation.BOOK_HIGH_ID)
      .fetch()
      .map { it.toRelation() }

  override fun findRelationsForBooks(bookIds: Set<String>): List<DedupRelation> {
    if (bookIds.isEmpty()) return emptyList()
    return dslRO
      .selectFrom(relation)
      .where(relation.BOOK_LOW_ID.`in`(bookIds))
      .and(relation.BOOK_HIGH_ID.`in`(bookIds))
      .orderBy(relation.BOOK_LOW_ID, relation.BOOK_HIGH_ID)
      .fetch()
      .map { it.toRelation() }
  }

  override fun findRelationsTouchingBooks(
    libraryId: String,
    bookIds: Set<String>,
  ): List<DedupRelation> {
    if (bookIds.isEmpty()) return emptyList()
    return dslRO
      .selectFrom(relation)
      .where(relation.LIBRARY_ID.eq(libraryId))
      .and(relation.BOOK_LOW_ID.`in`(bookIds).or(relation.BOOK_HIGH_ID.`in`(bookIds)))
      .orderBy(relation.BOOK_LOW_ID, relation.BOOK_HIGH_ID)
      .fetch()
      .map { it.toRelation() }
  }

  override fun saveRelation(value: DedupRelation) {
    dslRW
      .insertInto(
        relation,
        relation.ID,
        relation.LIBRARY_ID,
        relation.BOOK_LOW_ID,
        relation.BOOK_HIGH_ID,
        relation.LOW_CONTENT_GENERATION,
        relation.HIGH_CONTENT_GENERATION,
        relation.LOW_COVER_GENERATION,
        relation.HIGH_COVER_GENERATION,
        relation.LOW_METADATA_GENERATION,
        relation.HIGH_METADATA_GENERATION,
        relation.RELATION_TYPE,
        relation.CONTAINED_BOOK_ID,
        relation.CONTAINER_BOOK_ID,
        relation.COVER_DISTANCE,
        relation.COVERAGE_LEFT,
        relation.COVERAGE_RIGHT,
        relation.ORDER_CONSISTENCY,
        relation.LONGEST_MATCHED_RUN,
        relation.UNMATCHED_PREFIX_COUNT,
        relation.UNMATCHED_SUFFIX_COUNT,
        relation.UNMATCHED_INTERNAL_COUNT,
        relation.CONFIDENCE,
        relation.EVIDENCE_JSON,
        relation.FEATURE_SCHEMA_VERSION,
        relation.CLASSIFIER_RULE_VERSION,
        relation.STATUS,
        relation.CREATED_DATE,
        relation.LAST_MODIFIED_DATE,
      ).values(
        value.id,
        value.libraryId,
        value.bookLowId,
        value.bookHighId,
        value.lowContentGeneration,
        value.highContentGeneration,
        value.lowCoverGeneration,
        value.highCoverGeneration,
        value.lowMetadataGeneration,
        value.highMetadataGeneration,
        value.type.name,
        value.containedBookId,
        value.containerBookId,
        value.coverDistance,
        value.coverageLeft,
        value.coverageRight,
        value.orderConsistency,
        value.longestMatchedRun,
        value.unmatchedPrefixCount,
        value.unmatchedSuffixCount,
        value.unmatchedInternalCount,
        value.confidence,
        value.evidenceJson,
        value.featureSchemaVersion,
        value.classifierRuleVersion,
        value.status.name,
        value.createdDate,
        value.lastModifiedDate,
      ).onDuplicateKeyUpdate()
      .set(relation.ID, value.id)
      .set(relation.LIBRARY_ID, value.libraryId)
      .set(relation.LOW_CONTENT_GENERATION, value.lowContentGeneration)
      .set(relation.HIGH_CONTENT_GENERATION, value.highContentGeneration)
      .set(relation.LOW_COVER_GENERATION, value.lowCoverGeneration)
      .set(relation.HIGH_COVER_GENERATION, value.highCoverGeneration)
      .set(relation.LOW_METADATA_GENERATION, value.lowMetadataGeneration)
      .set(relation.HIGH_METADATA_GENERATION, value.highMetadataGeneration)
      .set(relation.RELATION_TYPE, value.type.name)
      .set(relation.CONTAINED_BOOK_ID, value.containedBookId)
      .set(relation.CONTAINER_BOOK_ID, value.containerBookId)
      .set(relation.COVER_DISTANCE, value.coverDistance)
      .set(relation.COVERAGE_LEFT, value.coverageLeft?.toFloat())
      .set(relation.COVERAGE_RIGHT, value.coverageRight?.toFloat())
      .set(relation.ORDER_CONSISTENCY, value.orderConsistency?.toFloat())
      .set(relation.LONGEST_MATCHED_RUN, value.longestMatchedRun)
      .set(relation.UNMATCHED_PREFIX_COUNT, value.unmatchedPrefixCount)
      .set(relation.UNMATCHED_SUFFIX_COUNT, value.unmatchedSuffixCount)
      .set(relation.UNMATCHED_INTERNAL_COUNT, value.unmatchedInternalCount)
      .set(relation.CONFIDENCE, value.confidence?.toFloat())
      .set(relation.EVIDENCE_JSON, value.evidenceJson)
      .set(relation.FEATURE_SCHEMA_VERSION, value.featureSchemaVersion)
      .set(relation.CLASSIFIER_RULE_VERSION, value.classifierRuleVersion)
      .set(relation.STATUS, value.status.name)
      .set(relation.LAST_MODIFIED_DATE, value.lastModifiedDate)
      .execute()
  }

  @Transactional
  override fun replaceExactRelationsForBook(
    bookId: String,
    relations: Collection<DedupRelation>,
    now: LocalDateTime,
  ) {
    val currentPairs = relations.map { it.bookLowId to it.bookHighId }.toSet()
    dslRW
      .select(relation.BOOK_LOW_ID, relation.BOOK_HIGH_ID)
      .from(relation)
      .where(relation.RELATION_TYPE.eq(DedupRelationType.EXACT_FILE.name))
      .and(relation.BOOK_LOW_ID.eq(bookId).or(relation.BOOK_HIGH_ID.eq(bookId)))
      .fetch()
      .map { it.value1() to it.value2() }
      .filterNot(currentPairs::contains)
      .forEach { (low, high) ->
        dslRW
          .update(relation)
          .set(relation.STATUS, DedupRelationStatus.STALE.name)
          .set(relation.LAST_MODIFIED_DATE, now)
          .where(relation.BOOK_LOW_ID.eq(low))
          .and(relation.BOOK_HIGH_ID.eq(high))
          .execute()
      }
    relations.forEach(::saveRelation)
  }

  @Transactional
  override fun replaceCoverRelationsForBook(
    bookId: String,
    relations: Collection<DedupRelation>,
    now: LocalDateTime,
  ) {
    val pairs = relations.map { it.bookLowId to it.bookHighId }.toSet()
    dslRW
      .select(relation.BOOK_LOW_ID, relation.BOOK_HIGH_ID)
      .from(relation)
      .where(relation.COVER_DISTANCE.isNotNull)
      .and(relation.BOOK_LOW_ID.eq(bookId).or(relation.BOOK_HIGH_ID.eq(bookId)))
      .fetch()
      .map { it.value1() to it.value2() }
      .filterNot(pairs::contains)
      .forEach { (low, high) ->
        dslRW
          .update(relation)
          .set(relation.COVER_DISTANCE, null as Int?)
          .set(relation.LOW_COVER_GENERATION, "")
          .set(relation.HIGH_COVER_GENERATION, "")
          .set(
            relation.STATUS,
            DSL
              .`when`(relation.RELATION_TYPE.eq(DedupRelationType.VISUALLY_SIMILAR.name), DedupRelationStatus.STALE.name)
              .otherwise(relation.STATUS),
          ).set(relation.LAST_MODIFIED_DATE, now)
          .where(relation.BOOK_LOW_ID.eq(low))
          .and(relation.BOOK_HIGH_ID.eq(high))
          .execute()
      }
    relations.forEach(::saveRelation)
  }

  override fun findPairDecisions(libraryId: String): List<DedupPairDecision> =
    dslRO
      .select(pairDecision.fields().toList())
      .from(pairDecision)
      .join(book)
      .on(book.ID.eq(pairDecision.BOOK_LOW_ID))
      .where(book.LIBRARY_ID.eq(libraryId))
      .orderBy(pairDecision.BOOK_LOW_ID, pairDecision.BOOK_HIGH_ID)
      .fetch {
        DedupPairDecision(
          bookLowId = it[pairDecision.BOOK_LOW_ID]!!,
          bookHighId = it[pairDecision.BOOK_HIGH_ID]!!,
          decision = DedupPairDecisionType.valueOf(it[pairDecision.DECISION]!!),
          resolutionId = it[pairDecision.RESOLUTION_ID],
          actorId = it[pairDecision.ACTOR_ID]!!,
          createdDate = it[pairDecision.CREATED_DATE]!!,
        )
      }

  @Transactional
  override fun savePairDecisions(decisions: Collection<DedupPairDecision>) {
    decisions.forEach { value ->
      dslRW
        .insertInto(
          pairDecision,
          pairDecision.BOOK_LOW_ID,
          pairDecision.BOOK_HIGH_ID,
          pairDecision.DECISION,
          pairDecision.RESOLUTION_ID,
          pairDecision.ACTOR_ID,
          pairDecision.CREATED_DATE,
        ).values(value.bookLowId, value.bookHighId, value.decision.name, value.resolutionId, value.actorId, value.createdDate)
        .onDuplicateKeyUpdate()
        .set(pairDecision.DECISION, value.decision.name)
        .set(pairDecision.RESOLUTION_ID, value.resolutionId)
        .set(pairDecision.ACTOR_ID, value.actorId)
        .execute()
    }
  }

  override fun findCluster(clusterId: String): DedupClusterWithMembers? {
    val value =
      dslRO
        .selectFrom(cluster)
        .where(cluster.ID.eq(clusterId))
        .fetchOne()
        ?.toCluster() ?: return null
    return DedupClusterWithMembers(value, findClusterMembers(setOf(clusterId))[clusterId].orEmpty())
  }

  override fun findAllClusters(libraryId: String?): List<DedupClusterWithMembers> {
    val values =
      dslRO
        .selectFrom(cluster)
        .where(libraryId?.let { cluster.LIBRARY_ID.eq(it) } ?: DSL.noCondition())
        .orderBy(cluster.CREATED_DATE, cluster.ID)
        .fetch()
        .map { it.toCluster() }
    val members = findClusterMembers(values.map { it.id }.toSet())
    return values.map { DedupClusterWithMembers(it, members[it.id].orEmpty()) }
  }

  override fun findClusters(
    libraryId: String?,
    status: DedupClusterStatus?,
    reviewable: Boolean?,
    evidenceMaturity: DedupEvidenceMaturity?,
    offset: Int,
    limit: Int,
  ): List<DedupClusterWithMembers> {
    require(offset >= 0 && limit in 1..100)
    val values =
      dslRO
        .selectFrom(cluster)
        .where(libraryId?.let { cluster.LIBRARY_ID.eq(it) } ?: DSL.noCondition())
        .and(status?.let { cluster.STATUS.eq(it.name) } ?: DSL.noCondition())
        .and(reviewable?.let { cluster.REVIEWABLE.eq(it) } ?: DSL.noCondition())
        .and(evidenceMaturity?.let { cluster.EVIDENCE_MATURITY.eq(it.name) } ?: DSL.noCondition())
        .and(cluster.SUPERSEDED_BY.isNull)
        .orderBy(cluster.LAST_MODIFIED_DATE.desc(), cluster.ID)
        .offset(offset)
        .limit(limit)
        .fetch()
        .map { it.toCluster() }
    val members = findClusterMembers(values.map { it.id }.toSet())
    return values.map { DedupClusterWithMembers(it, members[it.id].orEmpty()) }
  }

  override fun countClusters(
    libraryId: String?,
    status: DedupClusterStatus?,
    reviewable: Boolean?,
    evidenceMaturity: DedupEvidenceMaturity?,
  ): Long =
    dslRO
      .selectCount()
      .from(cluster)
      .where(libraryId?.let { cluster.LIBRARY_ID.eq(it) } ?: DSL.noCondition())
      .and(status?.let { cluster.STATUS.eq(it.name) } ?: DSL.noCondition())
      .and(reviewable?.let { cluster.REVIEWABLE.eq(it) } ?: DSL.noCondition())
      .and(evidenceMaturity?.let { cluster.EVIDENCE_MATURITY.eq(it.name) } ?: DSL.noCondition())
      .and(cluster.SUPERSEDED_BY.isNull)
      .fetchOne(0, Long::class.java) ?: 0L

  override fun countClustersByStatus(): Map<DedupClusterStatus, Int> =
    dslRO
      .select(cluster.STATUS, DSL.count())
      .from(cluster)
      .where(cluster.SUPERSEDED_BY.isNull)
      .groupBy(cluster.STATUS)
      .fetch()
      .associate { DedupClusterStatus.valueOf(it.value1()) to it.value2() }

  override fun findUnresolvedClusters(
    libraryId: String?,
    offset: Int,
    limit: Int,
  ): List<DedupClusterWithMembers> {
    require(offset >= 0 && limit in 1..100)
    val values =
      dslRO
        .selectFrom(cluster)
        .where(libraryId?.let { cluster.LIBRARY_ID.eq(it) } ?: DSL.noCondition())
        .and(cluster.STATUS.`in`(DedupClusterStatus.UNPROCESSED.name, DedupClusterStatus.NEEDS_ATTENTION.name))
        .and(cluster.REVIEWABLE.eq(true))
        .and(cluster.SUPERSEDED_BY.isNull)
        .orderBy(cluster.LAST_MODIFIED_DATE.desc(), cluster.ID)
        .offset(offset)
        .limit(limit)
        .fetch()
        .map { it.toCluster() }
    val members = findClusterMembers(values.map { it.id }.toSet())
    return values.map { DedupClusterWithMembers(it, members[it.id].orEmpty()) }
  }

  override fun countUnresolvedClusters(libraryId: String?): Long =
    dslRO
      .selectCount()
      .from(cluster)
      .where(libraryId?.let { cluster.LIBRARY_ID.eq(it) } ?: DSL.noCondition())
      .and(cluster.STATUS.`in`(DedupClusterStatus.UNPROCESSED.name, DedupClusterStatus.NEEDS_ATTENTION.name))
      .and(cluster.REVIEWABLE.eq(true))
      .and(cluster.SUPERSEDED_BY.isNull)
      .fetchOne(0, Long::class.java) ?: 0L

  override fun lockLibraryForClusterRebuild(libraryId: String) {
    check(
      dslRW
        .update(settings)
        .set(settings.LAST_MODIFIED_DATE, settings.LAST_MODIFIED_DATE)
        .where(settings.LIBRARY_ID.eq(libraryId))
        .execute() == 1,
    ) { "Dedup Library settings disappeared during cluster rebuild" }
  }

  @Transactional
  override fun saveCluster(
    value: DedupCluster,
    members: Collection<DedupClusterMember>,
  ) {
    dslRW
      .insertInto(
        cluster,
        cluster.ID,
        cluster.LIBRARY_ID,
        cluster.REVISION,
        cluster.STATUS,
        cluster.REVIEWABLE,
        cluster.ANCHOR_BOOK_ID,
        cluster.TOPOLOGY_FINGERPRINT,
        cluster.EVIDENCE_FINGERPRINT,
        cluster.STATE_FINGERPRINT,
        cluster.PROCESSED_REVISION,
        cluster.LAST_RESOLUTION_ID,
        cluster.REOPEN_REASON,
        cluster.SUPERSEDED_BY,
        cluster.CREATED_DATE,
        cluster.LAST_MODIFIED_DATE,
        cluster.PROCESSED_DATE,
        cluster.MEMBER_COUNT,
        cluster.VERIFIED_PAIR_COUNT,
        cluster.TOTAL_PAIR_COUNT,
        cluster.EVIDENCE_MATURITY,
      ).values(
        value.id,
        value.libraryId,
        value.revision,
        value.status.name,
        value.reviewable,
        value.anchorBookId,
        value.topologyFingerprint,
        value.evidenceFingerprint,
        value.stateFingerprint,
        value.processedRevision,
        value.lastResolutionId,
        value.reopenReason,
        value.supersededBy,
        value.createdDate,
        value.lastModifiedDate,
        value.processedDate,
        value.memberCount,
        value.verifiedPairCount,
        value.totalPairCount,
        value.evidenceMaturity.name,
      ).onDuplicateKeyUpdate()
      .set(cluster.LIBRARY_ID, value.libraryId)
      .set(cluster.REVISION, value.revision)
      .set(cluster.STATUS, value.status.name)
      .set(cluster.REVIEWABLE, value.reviewable)
      .set(cluster.ANCHOR_BOOK_ID, value.anchorBookId)
      .set(cluster.TOPOLOGY_FINGERPRINT, value.topologyFingerprint)
      .set(cluster.EVIDENCE_FINGERPRINT, value.evidenceFingerprint)
      .set(cluster.STATE_FINGERPRINT, value.stateFingerprint)
      .set(cluster.PROCESSED_REVISION, value.processedRevision)
      .set(cluster.LAST_RESOLUTION_ID, value.lastResolutionId)
      .set(cluster.REOPEN_REASON, value.reopenReason)
      .set(cluster.SUPERSEDED_BY, value.supersededBy)
      .set(cluster.LAST_MODIFIED_DATE, value.lastModifiedDate)
      .set(cluster.PROCESSED_DATE, value.processedDate)
      .set(cluster.MEMBER_COUNT, value.memberCount)
      .set(cluster.VERIFIED_PAIR_COUNT, value.verifiedPairCount)
      .set(cluster.TOTAL_PAIR_COUNT, value.totalPairCount)
      .set(cluster.EVIDENCE_MATURITY, value.evidenceMaturity.name)
      .execute()
    val supplied = members.map { it.bookId }.toSet()
    dslRW
      .select(clusterMember.BOOK_ID)
      .from(clusterMember)
      .where(clusterMember.CLUSTER_ID.eq(value.id))
      .fetch(clusterMember.BOOK_ID)
      .filterNot(supplied::contains)
      .forEach { bookId ->
        dslRW
          .update(clusterMember)
          .set(clusterMember.PRESENT, false)
          .set(clusterMember.LAST_MODIFIED_DATE, value.lastModifiedDate)
          .where(clusterMember.CLUSTER_ID.eq(value.id))
          .and(clusterMember.BOOK_ID.eq(bookId))
          .execute()
      }
    members.forEach { member ->
      dslRW
        .insertInto(
          clusterMember,
          clusterMember.CLUSTER_ID,
          clusterMember.BOOK_ID,
          clusterMember.PRESENT,
          clusterMember.SOURCE_CONTENT_GENERATION,
          clusterMember.SOURCE_COVER_GENERATION,
          clusterMember.SOURCE_METADATA_GENERATION,
          clusterMember.SERIES_SCOPE_REVISION,
          clusterMember.CREATED_DATE,
          clusterMember.LAST_MODIFIED_DATE,
        ).values(
          member.clusterId,
          member.bookId,
          member.present,
          member.sourceContentGeneration,
          member.sourceCoverGeneration,
          member.sourceMetadataGeneration,
          member.seriesScopeRevision,
          member.createdDate,
          member.lastModifiedDate,
        ).onDuplicateKeyUpdate()
        .set(clusterMember.PRESENT, member.present)
        .set(clusterMember.SOURCE_CONTENT_GENERATION, member.sourceContentGeneration)
        .set(clusterMember.SOURCE_COVER_GENERATION, member.sourceCoverGeneration)
        .set(clusterMember.SOURCE_METADATA_GENERATION, member.sourceMetadataGeneration)
        .set(clusterMember.SERIES_SCOPE_REVISION, member.seriesScopeRevision)
        .set(clusterMember.LAST_MODIFIED_DATE, member.lastModifiedDate)
        .execute()
    }
  }

  @Transactional
  override fun markClusterSuperseded(
    clusterId: String,
    supersededBy: String,
    now: LocalDateTime,
  ) {
    dslRW
      .update(cluster)
      .set(cluster.SUPERSEDED_BY, supersededBy)
      .set(cluster.REVIEWABLE, false)
      .set(cluster.LAST_MODIFIED_DATE, now)
      .where(cluster.ID.eq(clusterId))
      .and(cluster.SUPERSEDED_BY.isNull)
      .execute()
    dslRW
      .update(clusterMember)
      .set(clusterMember.PRESENT, false)
      .set(clusterMember.LAST_MODIFIED_DATE, now)
      .where(clusterMember.CLUSTER_ID.eq(clusterId))
      .execute()
  }

  override fun claimCluster(
    clusterId: String,
    expectedRevision: Long,
    stateFingerprint: String,
    now: LocalDateTime,
  ): Boolean =
    dslRW
      .update(cluster)
      .set(cluster.STATUS, DedupClusterStatus.PROCESSING.name)
      .set(cluster.LAST_MODIFIED_DATE, now)
      .where(cluster.ID.eq(clusterId))
      .and(cluster.REVISION.eq(expectedRevision))
      .and(cluster.STATE_FINGERPRINT.eq(stateFingerprint))
      .and(cluster.STATUS.eq(DedupClusterStatus.UNPROCESSED.name))
      .and(cluster.REVIEWABLE.eq(true))
      .and(cluster.SUPERSEDED_BY.isNull)
      .execute() == 1

  override fun updateClusterState(
    clusterId: String,
    expectedStatuses: Set<DedupClusterStatus>,
    newStatus: DedupClusterStatus,
    lastResolutionId: String?,
    reopenReason: String?,
    now: LocalDateTime,
  ): Boolean {
    var update = dslRW.update(cluster).set(cluster.STATUS, newStatus.name).set(cluster.LAST_MODIFIED_DATE, now)
    if (lastResolutionId != null) update = update.set(cluster.LAST_RESOLUTION_ID, lastResolutionId)
    if (reopenReason != null) update = update.set(cluster.REOPEN_REASON, reopenReason)
    return update.where(cluster.ID.eq(clusterId)).and(cluster.STATUS.`in`(expectedStatuses.map(DedupClusterStatus::name))).execute() == 1
  }

  override fun insertResolution(
    value: DedupResolution,
    members: Collection<DedupResolutionMember>,
  ) {
    dslRW.transaction { configuration ->
      val tx = DSL.using(configuration)
      tx
        .insertInto(
          resolution,
          resolution.ID,
          resolution.CLUSTER_ID,
          resolution.CLUSTER_REVISION,
          resolution.MODE,
          resolution.PLAN_REVISION,
          resolution.PLAN_JSON,
          resolution.EVIDENCE_JSON,
          resolution.ELIGIBILITY_JSON,
          resolution.RULE_VERSION,
          resolution.STATE,
          resolution.ACTOR_ID,
          resolution.RESULT_JSON,
          resolution.LEASE_TOKEN,
          resolution.LEASE_UNTIL,
          resolution.CREATED_DATE,
          resolution.LAST_MODIFIED_DATE,
          resolution.COMPLETED_DATE,
        ).values(
          value.id,
          value.clusterId,
          value.clusterRevision,
          value.mode.name,
          value.planRevision,
          value.planJson,
          value.evidenceJson,
          value.eligibilityJson,
          value.ruleVersion,
          value.state.name,
          value.actorId,
          value.resultJson,
          value.leaseToken,
          value.leaseUntil,
          value.createdDate,
          value.lastModifiedDate,
          value.completedDate,
        ).execute()
      members.forEach { member ->
        tx
          .insertInto(
            resolutionMember,
            resolutionMember.RESOLUTION_ID,
            resolutionMember.BOOK_ID,
            resolutionMember.SERIES_ID,
            resolutionMember.LIBRARY_ID,
            resolutionMember.ACTION,
            resolutionMember.KEEPER_BOOK_ID,
            resolutionMember.TITLE_SNAPSHOT,
            resolutionMember.PATH_SNAPSHOT,
            resolutionMember.SOURCE_GENERATIONS_JSON,
            resolutionMember.LOCAL_STATE_SNAPSHOT_JSON,
            resolutionMember.DIRECT_RELATION_ID,
            resolutionMember.DIRECT_RELATION_SNAPSHOT_JSON,
            resolutionMember.EXPECTED_PATH,
            resolutionMember.EXPECTED_SIZE,
            resolutionMember.EXPECTED_ARCHIVE_HASH,
            resolutionMember.STATE,
            resolutionMember.RESULT_CODE,
            resolutionMember.RESULT_JSON,
            resolutionMember.LAST_ERROR,
            resolutionMember.CREATED_DATE,
            resolutionMember.LAST_MODIFIED_DATE,
          ).values(
            member.resolutionId,
            member.bookId,
            member.seriesId,
            member.libraryId,
            member.action.name,
            member.keeperBookId,
            member.titleSnapshot,
            member.pathSnapshot,
            member.sourceGenerationsJson,
            member.localStateSnapshotJson,
            member.directRelationId,
            member.directRelationSnapshotJson,
            member.expectedPath,
            member.expectedSize,
            member.expectedArchiveHash,
            member.state.name,
            member.resultCode,
            member.resultJson,
            member.lastError,
            member.createdDate,
            member.lastModifiedDate,
          ).execute()
      }
    }
  }

  override fun findResolution(resolutionId: String): DedupResolution? =
    dslRO
      .selectFrom(resolution)
      .where(resolution.ID.eq(resolutionId))
      .fetchOne()
      ?.toResolution()

  override fun findResolutions(
    offset: Int,
    limit: Int,
  ): List<DedupResolution> =
    dslRO
      .selectFrom(resolution)
      .orderBy(resolution.CREATED_DATE.desc(), resolution.ID)
      .offset(offset)
      .limit(limit)
      .fetch()
      .map { it.toResolution() }

  override fun countResolutions(): Long = dslRO.selectCount().from(resolution).fetchOne(0, Long::class.java) ?: 0L

  override fun findProcessedResolutions(
    offset: Int,
    limit: Int,
  ): List<DedupResolution> =
    dslRO
      .selectFrom(resolution)
      .where(resolution.STATE.eq(DedupResolutionState.PROCESSED.name))
      .orderBy(resolution.COMPLETED_DATE.desc(), resolution.ID)
      .offset(offset)
      .limit(limit)
      .fetch()
      .map { it.toResolution() }

  override fun countProcessedResolutions(): Long =
    dslRO
      .selectCount()
      .from(resolution)
      .where(resolution.STATE.eq(DedupResolutionState.PROCESSED.name))
      .fetchOne(0, Long::class.java) ?: 0L

  override fun countResolutionsByState(): Map<DedupResolutionState, Int> =
    dslRO
      .select(resolution.STATE, DSL.count())
      .from(resolution)
      .groupBy(resolution.STATE)
      .fetch()
      .associate { DedupResolutionState.valueOf(it.value1()) to it.value2() }

  override fun findResolutionMembers(resolutionId: String): List<DedupResolutionMember> =
    dslRO
      .selectFrom(resolutionMember)
      .where(resolutionMember.RESOLUTION_ID.eq(resolutionId))
      .orderBy(resolutionMember.BOOK_ID)
      .fetch()
      .map { it.toResolutionMember() }

  override fun hasActiveResolutionForBooks(bookIds: Set<String>): Boolean {
    if (bookIds.isEmpty()) return false
    return dslRO.fetchExists(
      dslRO
        .selectOne()
        .from(resolutionMember)
        .join(resolution)
        .on(resolution.ID.eq(resolutionMember.RESOLUTION_ID))
        .where(resolutionMember.BOOK_ID.`in`(bookIds))
        .and(resolution.STATE.eq(DedupResolutionState.PROCESSING.name)),
    )
  }

  override fun hasResolutionAttempt(
    clusterId: String,
    clusterRevision: Long,
    mode: DedupResolutionMode,
    actorId: String,
  ): Boolean =
    dslRO.fetchExists(
      resolution,
      resolution.CLUSTER_ID
        .eq(clusterId)
        .and(resolution.CLUSTER_REVISION.eq(clusterRevision))
        .and(resolution.MODE.eq(mode.name))
        .and(resolution.ACTOR_ID.eq(actorId)),
    )

  override fun updateResolution(
    resolutionId: String,
    expectedStates: Set<DedupResolutionState>,
    state: DedupResolutionState,
    resultJson: String,
    completedDate: LocalDateTime?,
    leaseToken: String?,
    leaseUntil: LocalDateTime?,
    now: LocalDateTime,
  ): Boolean {
    var update =
      dslRW
        .update(resolution)
        .set(resolution.STATE, state.name)
        .set(resolution.RESULT_JSON, resultJson)
        .set(resolution.LAST_MODIFIED_DATE, now)
        .set(resolution.COMPLETED_DATE, completedDate)
    if (leaseToken != null) update = update.set(resolution.LEASE_TOKEN, leaseToken)
    if (leaseUntil != null) update = update.set(resolution.LEASE_UNTIL, leaseUntil)
    return update.where(resolution.ID.eq(resolutionId)).and(resolution.STATE.`in`(expectedStates.map(DedupResolutionState::name))).execute() == 1
  }

  override fun updateResolutionMember(
    resolutionId: String,
    bookId: String,
    expectedStates: Set<DedupResolutionMemberState>,
    state: DedupResolutionMemberState,
    expectedPath: String?,
    expectedSize: Long?,
    expectedArchiveHash: String?,
    resultCode: String?,
    resultJson: String?,
    lastError: String?,
    now: LocalDateTime,
  ): Boolean {
    var update =
      dslRW
        .update(resolutionMember)
        .set(resolutionMember.STATE, state.name)
        .set(resolutionMember.RESULT_CODE, resultCode)
        .set(resolutionMember.RESULT_JSON, resultJson)
        .set(resolutionMember.LAST_ERROR, lastError?.take(500))
        .set(resolutionMember.LAST_MODIFIED_DATE, now)
    if (expectedPath != null) update = update.set(resolutionMember.EXPECTED_PATH, expectedPath)
    if (expectedSize != null) update = update.set(resolutionMember.EXPECTED_SIZE, expectedSize)
    if (expectedArchiveHash != null) update = update.set(resolutionMember.EXPECTED_ARCHIVE_HASH, expectedArchiveHash)
    return update
      .where(resolutionMember.RESOLUTION_ID.eq(resolutionId))
      .and(resolutionMember.BOOK_ID.eq(bookId))
      .and(resolutionMember.STATE.`in`(expectedStates.map(DedupResolutionMemberState::name)))
      .execute() == 1
  }

  @Transactional
  override fun releaseExpiredResolutionLeases(now: LocalDateTime): Int {
    val orphaned =
      dslRW
        .update(cluster)
        .set(cluster.STATUS, DedupClusterStatus.NEEDS_ATTENTION.name)
        .set(cluster.REOPEN_REASON, "PROCESSING_LEASE_EXPIRED")
        .set(cluster.LAST_MODIFIED_DATE, now)
        .where(cluster.STATUS.eq(DedupClusterStatus.PROCESSING.name))
        .and(cluster.LAST_MODIFIED_DATE.lt(now.minusMinutes(30)))
        .andNotExists(
          dslRW
            .selectOne()
            .from(resolution)
            .where(resolution.CLUSTER_ID.eq(cluster.ID))
            .and(resolution.STATE.eq(DedupResolutionState.PROCESSING.name)),
        ).execute()
    val expired =
      dslRW
        .select(resolution.ID, resolution.CLUSTER_ID)
        .from(resolution)
        .where(resolution.STATE.eq(DedupResolutionState.PROCESSING.name))
        .and(resolution.LEASE_UNTIL.lt(now))
        .fetch()
    if (expired.isEmpty()) return orphaned
    expired.forEach { value ->
      dslRW
        .update(cluster)
        .set(cluster.STATUS, DedupClusterStatus.NEEDS_ATTENTION.name)
        .set(cluster.REOPEN_REASON, "PROCESSING_LEASE_EXPIRED")
        .set(cluster.LAST_MODIFIED_DATE, now)
        .where(cluster.ID.eq(value.value2()))
        .and(cluster.LAST_RESOLUTION_ID.eq(value.value1()))
        .and(cluster.STATUS.`in`(DedupClusterStatus.PROCESSING.name, DedupClusterStatus.PROCESSED.name))
        .execute()
    }
    return orphaned +
      expired.sumOf { value ->
        val partial =
          dslRW.fetchExists(
            dslRW
              .selectOne()
              .from(resolutionMember)
              .where(resolutionMember.RESOLUTION_ID.eq(value.value1()))
              .and(
                resolutionMember.STATE.`in`(
                  DedupResolutionMemberState.DELETED.name,
                  DedupResolutionMemberState.KOMGA_SAVED.name,
                  DedupResolutionMemberState.GORSE_CONFIRMED.name,
                  DedupResolutionMemberState.COMPLETED.name,
                ),
              ),
          )
        dslRW
          .update(resolution)
          .set(resolution.STATE, if (partial) DedupResolutionState.PARTIALLY_COMPLETED.name else DedupResolutionState.NEEDS_ATTENTION.name)
          .set(resolution.RESULT_JSON, "{\"code\":\"PROCESSING_LEASE_EXPIRED\",\"partial\":$partial}")
          .set(resolution.LAST_MODIFIED_DATE, now)
          .where(resolution.ID.eq(value.value1()))
          .and(resolution.STATE.eq(DedupResolutionState.PROCESSING.name))
          .execute()
      }
  }

  override fun enqueueGorseSync(
    seriesId: String,
    libraryId: String,
    desiredHidden: Boolean,
    now: LocalDateTime,
  ) {
    dslRW
      .insertInto(
        gorseSync,
        gorseSync.SERIES_ID,
        gorseSync.LIBRARY_ID,
        gorseSync.DESIRED_HIDDEN,
        gorseSync.STATE,
        gorseSync.ATTEMPT_COUNT,
        gorseSync.CREATED_DATE,
        gorseSync.LAST_MODIFIED_DATE,
      ).values(seriesId, libraryId, desiredHidden, "PENDING", 0, now, now)
      .onDuplicateKeyUpdate()
      .set(gorseSync.LIBRARY_ID, libraryId)
      .set(gorseSync.DESIRED_HIDDEN, desiredHidden)
      .set(gorseSync.STATE, "PENDING")
      .set(gorseSync.NEXT_RETRY_AT, null as LocalDateTime?)
      .set(gorseSync.LAST_ERROR, null as String?)
      .set(gorseSync.COMPLETED_DATE, null as LocalDateTime?)
      .set(gorseSync.LAST_MODIFIED_DATE, now)
      .execute()
  }

  @Transactional
  override fun findPendingGorseSync(now: LocalDateTime): DedupGorseSync? {
    repeat(3) {
      val candidate =
        dslRW
          .selectFrom(gorseSync)
          .where(
            gorseSync.STATE
              .`in`("PENDING", "FAILED_REVIEW")
              .and(gorseSync.NEXT_RETRY_AT.isNull.or(gorseSync.NEXT_RETRY_AT.le(now)))
              .or(gorseSync.STATE.eq("RUNNING").and(gorseSync.LAST_MODIFIED_DATE.le(now.minusMinutes(10)))),
          ).orderBy(gorseSync.LAST_MODIFIED_DATE)
          .limit(1)
          .fetchOne() ?: return null
      val updated =
        dslRW
          .update(gorseSync)
          .set(gorseSync.STATE, "RUNNING")
          .set(gorseSync.LAST_MODIFIED_DATE, now)
          .where(gorseSync.SERIES_ID.eq(candidate.seriesId))
          .and(gorseSync.STATE.eq(candidate.state))
          .and(gorseSync.DESIRED_HIDDEN.eq(candidate.desiredHidden))
          .and(gorseSync.LAST_MODIFIED_DATE.eq(candidate.lastModifiedDate))
          .execute()
      if (updated == 1) return findGorseSync(candidate.seriesId!!)
    }
    return null
  }

  override fun findGorseSync(seriesId: String): DedupGorseSync? =
    dslRO
      .selectFrom(gorseSync)
      .where(gorseSync.SERIES_ID.eq(seriesId))
      .fetchOne()
      ?.toGorseSync()

  override fun completeGorseSync(
    seriesId: String,
    expectedHidden: Boolean,
    now: LocalDateTime,
  ): Boolean =
    dslRW
      .update(gorseSync)
      .set(gorseSync.STATE, "SUCCEEDED")
      .set(gorseSync.NEXT_RETRY_AT, null as LocalDateTime?)
      .set(gorseSync.LAST_ERROR, null as String?)
      .set(gorseSync.COMPLETED_DATE, now)
      .set(gorseSync.LAST_MODIFIED_DATE, now)
      .where(gorseSync.SERIES_ID.eq(seriesId))
      .and(gorseSync.DESIRED_HIDDEN.eq(expectedHidden))
      .and(gorseSync.STATE.`in`("PENDING", "RUNNING"))
      .execute() == 1

  override fun failGorseSync(
    seriesId: String,
    expectedHidden: Boolean,
    error: String,
    now: LocalDateTime,
  ): Boolean =
    dslRW
      .update(gorseSync)
      .set(gorseSync.STATE, "FAILED_REVIEW")
      .set(gorseSync.ATTEMPT_COUNT, gorseSync.ATTEMPT_COUNT.plus(1))
      .set(gorseSync.NEXT_RETRY_AT, now.plusSeconds(30))
      .set(gorseSync.LAST_ERROR, error.take(500))
      .set(gorseSync.LAST_MODIFIED_DATE, now)
      .where(gorseSync.SERIES_ID.eq(seriesId))
      .and(gorseSync.DESIRED_HIDDEN.eq(expectedHidden))
      .and(gorseSync.STATE.`in`("PENDING", "RUNNING"))
      .execute() == 1

  override fun countGorseSyncStates(): Map<String, Int> =
    dslRO
      .select(gorseSync.STATE, DSL.count())
      .from(gorseSync)
      .groupBy(gorseSync.STATE)
      .fetch()
      .associate { it.value1() to it.value2() }

  private fun findClusterMembers(clusterIds: Set<String>): Map<String, List<DedupClusterMember>> {
    if (clusterIds.isEmpty()) return emptyMap()
    return dslRO
      .selectFrom(clusterMember)
      .where(clusterMember.CLUSTER_ID.`in`(clusterIds))
      .orderBy(clusterMember.CLUSTER_ID, clusterMember.BOOK_ID)
      .fetch()
      .map { it.toClusterMember() }
      .groupBy { it.clusterId }
  }

  private fun DedupLibrarySettingsRecord.toDomain() =
    DedupLibrarySettings(
      libraryId = libraryId!!,
      enabled = enabled!!,
      paused = paused!!,
      scanInterval = Library.ScanInterval.valueOf(scanInterval!!),
      batchSize = batchSize!!,
      maxDurationSeconds = maxDurationSeconds!!,
      quietPeriodSeconds = quietPeriodSeconds!!,
      coverCandidateDistance = coverCandidateDistance!!,
      coverTopK = coverTopK!!,
      autoResolveSuggestions = autoResolveSuggestions!!,
      createdDate = createdDate!!,
      lastModifiedDate = lastModifiedDate!!,
      lastBatchDate = lastBatchDate,
      lastBatchBookCount = lastBatchBookCount!!,
    )

  private fun DedupWorkRecord.toDomain() =
    DedupWork(
      id!!,
      libraryId!!,
      DedupWorkType.valueOf(type!!),
      targetKey!!,
      DedupWorkState.valueOf(state!!),
      desiredRevision!!,
      completedRevision!!,
      notBefore!!,
      nextRetryAt,
      leaseOwner,
      leaseToken,
      leaseUntil,
      attemptCount!!,
      maxAttempts!!,
      lastErrorCode,
      lastError,
      priority!!,
      createdDate!!,
      lastModifiedDate!!,
      completedDate,
    )

  private fun DedupFeatureRecord.toDomain() =
    DedupFeature(
      bookId!!,
      seriesId!!,
      libraryId!!,
      sourceContentGeneration!!,
      sourceCoverGeneration!!,
      sourceMetadataGeneration!!,
      seriesScopeRevision!!,
      featureSchemaVersion!!,
      DedupFeatureState.valueOf(coverState!!),
      DedupFeatureState.valueOf(pageState!!),
      coverSource,
      coverHash,
      coverQuality,
      pageCount,
      analyzedDate,
      lastModifiedDate!!,
      archiveHash,
      archiveHashPath,
      archiveHashSize,
      archiveHashSchemaVersion,
      archiveHashDate,
    )

  private fun org.gotson.komga.jooq.main.tables.records.DedupRelationRecord.toRelation() =
    DedupRelation(
      id!!,
      libraryId!!,
      bookLowId!!,
      bookHighId!!,
      lowContentGeneration!!,
      highContentGeneration!!,
      lowCoverGeneration!!,
      highCoverGeneration!!,
      lowMetadataGeneration!!,
      highMetadataGeneration!!,
      DedupRelationType.valueOf(relationType!!),
      coverDistance,
      containedBookId,
      containerBookId,
      coverageLeft?.toDouble(),
      coverageRight?.toDouble(),
      orderConsistency?.toDouble(),
      longestMatchedRun,
      unmatchedPrefixCount,
      unmatchedSuffixCount,
      unmatchedInternalCount,
      confidence?.toDouble(),
      DedupRelationStatus.valueOf(status!!),
      evidenceJson!!,
      featureSchemaVersion!!,
      classifierRuleVersion!!,
      createdDate!!,
      lastModifiedDate!!,
    )

  private fun org.gotson.komga.jooq.main.tables.records.DedupClusterRecord.toCluster() =
    DedupCluster(
      id!!,
      libraryId!!,
      revision!!,
      DedupClusterStatus.valueOf(status!!),
      reviewable!!,
      anchorBookId!!,
      topologyFingerprint!!,
      evidenceFingerprint!!,
      stateFingerprint!!,
      processedRevision,
      lastResolutionId,
      reopenReason,
      supersededBy,
      createdDate!!,
      lastModifiedDate!!,
      processedDate,
      memberCount!!,
      verifiedPairCount!!,
      totalPairCount!!,
      DedupEvidenceMaturity.valueOf(evidenceMaturity!!),
    )

  private fun org.gotson.komga.jooq.main.tables.records.DedupClusterMemberRecord.toClusterMember() =
    DedupClusterMember(
      clusterId!!,
      bookId!!,
      present!!,
      sourceContentGeneration!!,
      sourceCoverGeneration!!,
      sourceMetadataGeneration!!,
      seriesScopeRevision!!,
      createdDate!!,
      lastModifiedDate!!,
    )

  private fun org.gotson.komga.jooq.main.tables.records.DedupResolutionRecord.toResolution() =
    DedupResolution(
      id!!,
      clusterId!!,
      clusterRevision!!,
      DedupResolutionMode.valueOf(mode!!),
      planRevision!!,
      planJson!!,
      evidenceJson!!,
      eligibilityJson!!,
      ruleVersion!!,
      DedupResolutionState.valueOf(state!!),
      actorId!!,
      resultJson!!,
      leaseToken!!,
      leaseUntil!!,
      createdDate!!,
      lastModifiedDate!!,
      completedDate,
    )

  private fun org.gotson.komga.jooq.main.tables.records.DedupResolutionMemberRecord.toResolutionMember() =
    DedupResolutionMember(
      resolutionId!!,
      bookId!!,
      seriesId!!,
      libraryId!!,
      DedupResolutionAction.valueOf(action!!),
      keeperBookId,
      titleSnapshot!!,
      pathSnapshot!!,
      sourceGenerationsJson!!,
      localStateSnapshotJson!!,
      directRelationId,
      directRelationSnapshotJson,
      expectedPath,
      expectedSize,
      expectedArchiveHash,
      DedupResolutionMemberState.valueOf(state!!),
      resultCode,
      resultJson,
      lastError,
      createdDate!!,
      lastModifiedDate!!,
    )

  private fun org.gotson.komga.jooq.main.tables.records.DedupGorseSyncRecord.toGorseSync() =
    DedupGorseSync(
      seriesId!!,
      libraryId!!,
      desiredHidden!!,
      state!!,
      attemptCount!!,
      nextRetryAt,
      lastError,
      createdDate!!,
      lastModifiedDate!!,
      completedDate,
    )

  private fun canonicalPair(
    first: String,
    second: String,
  ): Pair<String, String> = if (first < second) first to second else second to first
}
