package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.DedupDecision
import org.gotson.komga.domain.model.DedupDecisionItem
import org.gotson.komga.domain.model.DedupDecisionItemState
import org.gotson.komga.domain.model.DedupDecisionMode
import org.gotson.komga.domain.model.DedupDecisionState
import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupFeatureState
import org.gotson.komga.domain.model.DedupGorseSync
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupOverride
import org.gotson.komga.domain.model.DedupOverrideType
import org.gotson.komga.domain.model.DedupPageFeature
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupReviewCase
import org.gotson.komga.domain.model.DedupReviewCaseCandidate
import org.gotson.komga.domain.model.DedupReviewCaseOrigin
import org.gotson.komga.domain.model.DedupReviewCaseStatus
import org.gotson.komga.domain.model.DedupWork
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.model.Library
import org.gotson.komga.domain.persistence.DedupDecisionRepository
import org.gotson.komga.domain.persistence.DedupRepository
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
  DedupDecisionRepository {
  private val settings = Tables.DEDUP_LIBRARY_SETTINGS
  private val work = Tables.DEDUP_WORK
  private val relation = Tables.DEDUP_RELATION
  private val reviewCase = Tables.DEDUP_REVIEW_CASE
  private val reviewMember = Tables.DEDUP_REVIEW_CASE_MEMBER
  private val override = Tables.DEDUP_OVERRIDE
  private val decision = Tables.DEDUP_DECISION
  private val decisionItem = Tables.DEDUP_DECISION_ITEM
  private val gorseSync = Tables.DEDUP_GORSE_SYNC
  private val feature = Tables.DEDUP_FEATURE
  private val pageFeature = Tables.DEDUP_PAGE_FEATURE

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

  override fun saveLibrarySettings(settings: DedupLibrarySettings) {
    dslRW
      .insertInto(
        this.settings,
        this.settings.LIBRARY_ID,
        this.settings.ENABLED,
        this.settings.PAUSED,
        this.settings.SCAN_INTERVAL,
        this.settings.BATCH_SIZE,
        this.settings.MAX_DURATION_SECONDS,
        this.settings.QUIET_PERIOD_SECONDS,
        this.settings.COMPLETION_STABILITY_SECONDS,
        this.settings.COVER_CANDIDATE_DISTANCE,
        this.settings.COVER_TOP_K,
        this.settings.CREATED_DATE,
        this.settings.LAST_MODIFIED_DATE,
      ).values(
        settings.libraryId,
        settings.enabled,
        settings.paused,
        settings.scanInterval.name,
        settings.batchSize,
        settings.maxDurationSeconds,
        settings.quietPeriodSeconds,
        settings.completionStabilitySeconds,
        settings.coverCandidateDistance,
        settings.coverTopK,
        settings.createdDate,
        settings.lastModifiedDate,
      ).onDuplicateKeyUpdate()
      .set(this.settings.ENABLED, settings.enabled)
      .set(this.settings.PAUSED, settings.paused)
      .set(this.settings.SCAN_INTERVAL, settings.scanInterval.name)
      .set(this.settings.BATCH_SIZE, settings.batchSize)
      .set(this.settings.MAX_DURATION_SECONDS, settings.maxDurationSeconds)
      .set(this.settings.QUIET_PERIOD_SECONDS, settings.quietPeriodSeconds)
      .set(this.settings.COMPLETION_STABILITY_SECONDS, settings.completionStabilitySeconds)
      .set(this.settings.COVER_CANDIDATE_DISTANCE, settings.coverCandidateDistance)
      .set(this.settings.COVER_TOP_K, settings.coverTopK)
      .set(this.settings.LAST_MODIFIED_DATE, settings.lastModifiedDate)
      .execute()
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
    require(maxAttempts > 0) { "Maximum attempts must be positive" }
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
      ).values(
        id,
        libraryId,
        type.name,
        targetKey,
        DedupWorkState.WAITING.name,
        1L,
        0L,
        notBefore,
        0,
        maxAttempts,
        priority,
        now,
        now,
      ).onDuplicateKeyUpdate()
      .set(work.DESIRED_REVISION, work.DESIRED_REVISION.plus(1L))
      .set(
        work.STATE,
        DSL
          .`when`(work.STATE.eq(DedupWorkState.RUNNING.name), DedupWorkState.RUNNING.name)
          .otherwise(DedupWorkState.WAITING.name),
      ).set(work.NOT_BEFORE, notBefore)
      .set(work.NEXT_RETRY_AT, null as LocalDateTime?)
      .set(
        work.ATTEMPT_COUNT,
        DSL.`when`(work.STATE.eq(DedupWorkState.RUNNING.name), work.ATTEMPT_COUNT).otherwise(0),
      ).set(work.MAX_ATTEMPTS, maxAttempts)
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
    require(!leaseDuration.isNegative && !leaseDuration.isZero) { "Lease duration must be positive" }

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

      val leaseToken = UUID.randomUUID().toString()
      val updated =
        dslRW
          .update(work)
          .set(work.STATE, DedupWorkState.RUNNING.name)
          .set(work.LEASE_OWNER, owner)
          .set(work.LEASE_TOKEN, leaseToken)
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
    val pendingState =
      DSL
        .`when`(work.NOT_BEFORE.gt(now), DedupWorkState.WAITING.name)
        .otherwise(DedupWorkState.PENDING.name)
    val hasNewerRevision = work.DESIRED_REVISION.gt(completedRevision)

    return dslRW
      .update(work)
      .set(work.COMPLETED_REVISION, completedRevision)
      .set(work.STATE, DSL.`when`(hasNewerRevision, pendingState).otherwise(DedupWorkState.SUCCEEDED.name))
      .set(work.LEASE_OWNER, null as String?)
      .set(work.LEASE_TOKEN, null as String?)
      .set(work.LEASE_UNTIL, null as LocalDateTime?)
      .set(work.ATTEMPT_COUNT, 0)
      .set(work.NEXT_RETRY_AT, null as LocalDateTime?)
      .set(work.LAST_ERROR_CODE, null as String?)
      .set(work.LAST_ERROR, null as String?)
      .set(work.COMPLETED_DATE, DSL.`when`(hasNewerRevision, null as LocalDateTime?).otherwise(now))
      .set(work.LAST_MODIFIED_DATE, now)
      .where(work.ID.eq(workId))
      .and(work.STATE.eq(DedupWorkState.RUNNING.name))
      .and(work.LEASE_TOKEN.eq(leaseToken))
      .and(work.COMPLETED_REVISION.lt(completedRevision))
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

    val newerRevisionPending = current.desiredRevision!! > attemptedRevision
    val attempts = if (newerRevisionPending) 0 else current.attemptCount!! + 1
    val terminal = !newerRevisionPending && attempts >= current.maxAttempts!!
    val retryAt =
      if (newerRevisionPending || terminal) {
        null
      } else {
        val exponent = min(attempts - 1, 10)
        now.plusSeconds(min(3_600L, 5L * (1L shl exponent)))
      }

    return dslRW
      .update(work)
      .set(
        work.STATE,
        when {
          terminal -> DedupWorkState.FAILED_REVIEW.name
          else -> DedupWorkState.WAITING.name
        },
      ).set(work.LEASE_OWNER, null as String?)
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

  override fun findFeature(bookId: String): DedupFeature? =
    dslRO
      .selectFrom(feature)
      .where(feature.BOOK_ID.eq(bookId))
      .fetchOne()
      ?.toDomain()

  override fun findReadyCoverFeatures(libraryId: String): List<DedupFeature> =
    dslRO
      .selectFrom(feature)
      .where(feature.LIBRARY_ID.eq(libraryId))
      .and(feature.COVER_STATE.eq(DedupFeatureState.READY.name))
      .and(feature.COVER_HASH.isNotNull)
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
      .execute()
  }

  override fun deleteFeaturesNotIn(
    libraryId: String,
    activeBookIds: Set<String>,
  ): Int {
    val staleIds =
      dslRO
        .select(feature.BOOK_ID)
        .from(feature)
        .where(feature.LIBRARY_ID.eq(libraryId))
        .fetch(feature.BOOK_ID)
        .filterNot(activeBookIds::contains)
    return staleIds.chunked(500).sumOf { ids ->
      dslRW.deleteFrom(feature).where(feature.BOOK_ID.`in`(ids)).execute()
    }
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
        DedupPageFeature(
          bookId = it.bookId!!,
          sourceContentGeneration = it.sourceContentGeneration!!,
          featureSchemaVersion = it.featureSchemaVersion!!,
          pageNumber = it.pageNumber!!,
          exactHash = it.exactHash,
          perceptualHash = it.perceptualHash,
          quality = it.quality,
        )
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
        ).values(
          bookId,
          sourceContentGeneration,
          featureSchemaVersion,
          value.pageNumber,
          value.exactHash,
          value.perceptualHash,
          value.quality,
        ).execute()
    }
  }

  @Transactional
  override fun replaceReviewCases(
    libraryId: String,
    origin: DedupReviewCaseOrigin,
    candidates: Collection<DedupReviewCaseCandidate>,
    now: LocalDateTime,
  ) {
    require(candidates.all { it.libraryId == libraryId && it.origin == origin })

    val existing = findReviewCases(libraryId, origin).associateBy { it.id }
    val incomingIds = candidates.map { it.id }.toSet()
    val removedIds = existing.keys - incomingIds
    if (removedIds.isNotEmpty()) {
      dslRW.deleteFrom(reviewMember).where(reviewMember.CASE_ID.`in`(removedIds)).execute()
      dslRW.deleteFrom(reviewCase).where(reviewCase.ID.`in`(removedIds)).execute()
    }

    candidates.forEach { candidate -> upsertReviewCaseCandidate(candidate, existing[candidate.id], now) }

    val relations = candidates.flatMap { it.relations }
    if (origin == DedupReviewCaseOrigin.EXACT_FILE) {
      val relationIds = relations.map { it.id }.toSet()
      val delete =
        dslRW
          .deleteFrom(relation)
          .where(relation.LIBRARY_ID.eq(libraryId))
          .and(relation.RELATION_TYPE.eq(DedupRelationType.EXACT_FILE.name))
      if (relationIds.isNotEmpty()) delete.and(relation.ID.notIn(relationIds)).execute() else delete.execute()
    } else if (origin == DedupReviewCaseOrigin.COVER_SIMILARITY) {
      val relationIds = relations.map { it.id }.toSet()
      val delete =
        dslRW
          .deleteFrom(relation)
          .where(relation.LIBRARY_ID.eq(libraryId))
          .and(relation.RELATION_TYPE.eq(DedupRelationType.VISUALLY_SIMILAR.name))
      if (relationIds.isNotEmpty()) delete.and(relation.ID.notIn(relationIds)).execute() else delete.execute()
    }
    relations.forEach(::upsertRelation)
  }

  @Transactional
  override fun saveReviewCase(
    candidate: DedupReviewCaseCandidate,
    now: LocalDateTime,
  ) {
    upsertReviewCaseCandidate(candidate, findReviewCase(candidate.id), now)
    candidate.relations.forEach(::upsertRelation)
  }

  private fun upsertReviewCaseCandidate(
    candidate: DedupReviewCaseCandidate,
    previous: DedupReviewCase?,
    now: LocalDateTime,
  ) {
    val relationChanged =
      candidate.relations.any { incoming ->
        val current = findRelation(incoming.bookLowId, incoming.bookHighId)
        current == null ||
          current.type != incoming.type ||
          current.lowContentGeneration != incoming.lowContentGeneration ||
          current.highContentGeneration != incoming.highContentGeneration ||
          current.lowMetadataGeneration != incoming.lowMetadataGeneration ||
          current.highMetadataGeneration != incoming.highMetadataGeneration ||
          (
            incoming.type == DedupRelationType.VISUALLY_SIMILAR &&
              (
                current.lowCoverGeneration != incoming.lowCoverGeneration ||
                  current.highCoverGeneration != incoming.highCoverGeneration
              )
          )
      }
    val changed = previous == null || previous.memberBookIds != candidate.memberBookIds || relationChanged
    if (!changed) return
    val revision = (previous?.revision ?: 0L) + 1L
    val createdDate = previous?.createdDate ?: now
    dslRW
      .insertInto(
        reviewCase,
        reviewCase.ID,
        reviewCase.LIBRARY_ID,
        reviewCase.REVISION,
        reviewCase.STATUS,
        reviewCase.ORIGIN,
        reviewCase.CREATED_DATE,
        reviewCase.LAST_MODIFIED_DATE,
      ).values(
        candidate.id,
        candidate.libraryId,
        revision,
        DedupReviewCaseStatus.REVIEW_REQUIRED.name,
        candidate.origin.name,
        createdDate,
        now,
      ).onDuplicateKeyUpdate()
      .set(reviewCase.REVISION, revision)
      .set(reviewCase.STATUS, DedupReviewCaseStatus.REVIEW_REQUIRED.name)
      .set(reviewCase.SUGGESTED_KEEPER_BOOK_ID, null as String?)
      .set(reviewCase.LAST_MODIFIED_DATE, now)
      .execute()

    dslRW.deleteFrom(reviewMember).where(reviewMember.CASE_ID.eq(candidate.id)).execute()
    candidate.memberBookIds.forEach { bookId -> dslRW.insertInto(reviewMember).values(candidate.id, bookId).execute() }
  }

  private fun upsertRelation(value: DedupRelation) {
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
        value.coverageLeft?.toFloat(),
        value.coverageRight?.toFloat(),
        value.orderConsistency?.toFloat(),
        value.longestMatchedRun,
        value.unmatchedPrefixCount,
        value.unmatchedSuffixCount,
        value.unmatchedInternalCount,
        value.evidenceJson,
        value.featureSchemaVersion,
        value.classifierRuleVersion,
        value.status.name,
        value.createdDate,
        value.lastModifiedDate,
      ).onDuplicateKeyUpdate()
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
      .set(relation.EVIDENCE_JSON, value.evidenceJson)
      .set(relation.FEATURE_SCHEMA_VERSION, value.featureSchemaVersion)
      .set(relation.CLASSIFIER_RULE_VERSION, value.classifierRuleVersion)
      .set(relation.STATUS, value.status.name)
      .set(relation.LAST_MODIFIED_DATE, value.lastModifiedDate)
      .execute()
  }

  override fun findReviewCase(caseId: String): DedupReviewCase? =
    dslRO.selectFrom(reviewCase).where(reviewCase.ID.eq(caseId)).fetchOne()?.let { record ->
      record.toDomain(findCaseMembers(setOf(caseId))[caseId].orEmpty())
    }

  override fun findRelation(
    firstBookId: String,
    secondBookId: String,
  ): DedupRelation? {
    val (low, high) = listOf(firstBookId, secondBookId).sorted()
    return dslRO
      .selectFrom(relation)
      .where(relation.BOOK_LOW_ID.eq(low))
      .and(relation.BOOK_HIGH_ID.eq(high))
      .fetchOne()
      ?.let {
        DedupRelation(
          id = it.id!!,
          libraryId = it.libraryId!!,
          bookLowId = it.bookLowId!!,
          bookHighId = it.bookHighId!!,
          lowContentGeneration = it.lowContentGeneration!!,
          highContentGeneration = it.highContentGeneration!!,
          lowCoverGeneration = it.lowCoverGeneration!!,
          highCoverGeneration = it.highCoverGeneration!!,
          lowMetadataGeneration = it.lowMetadataGeneration!!,
          highMetadataGeneration = it.highMetadataGeneration!!,
          type = DedupRelationType.valueOf(it.relationType!!),
          containedBookId = it.containedBookId,
          containerBookId = it.containerBookId,
          coverDistance = it.coverDistance,
          coverageLeft = it.coverageLeft?.toDouble(),
          coverageRight = it.coverageRight?.toDouble(),
          orderConsistency = it.orderConsistency?.toDouble(),
          longestMatchedRun = it.longestMatchedRun,
          unmatchedPrefixCount = it.unmatchedPrefixCount,
          unmatchedSuffixCount = it.unmatchedSuffixCount,
          unmatchedInternalCount = it.unmatchedInternalCount,
          status =
            org.gotson.komga.domain.model.DedupRelationStatus
              .valueOf(it.status!!),
          evidenceJson = it.evidenceJson!!,
          featureSchemaVersion = it.featureSchemaVersion!!,
          classifierRuleVersion = it.classifierRuleVersion!!,
          createdDate = it.createdDate!!,
          lastModifiedDate = it.lastModifiedDate!!,
        )
      }
  }

  override fun setReviewCaseKeeper(
    caseId: String,
    expectedRevision: Long,
    bookId: String,
    now: LocalDateTime,
  ): Boolean =
    dslRW
      .update(reviewCase)
      .set(reviewCase.SUGGESTED_KEEPER_BOOK_ID, bookId)
      .set(reviewCase.REVISION, reviewCase.REVISION.plus(1L))
      .set(reviewCase.LAST_MODIFIED_DATE, now)
      .where(reviewCase.ID.eq(caseId))
      .and(reviewCase.REVISION.eq(expectedRevision))
      .andExists(
        DSL
          .selectOne()
          .from(reviewMember)
          .where(reviewMember.CASE_ID.eq(caseId))
          .and(reviewMember.BOOK_ID.eq(bookId)),
      ).execute() == 1

  @Transactional
  override fun applyOverride(
    caseId: String,
    expectedRevision: Long,
    override: DedupOverride,
    newStatus: DedupReviewCaseStatus,
    now: LocalDateTime,
  ): Boolean {
    val updated =
      dslRW
        .update(reviewCase)
        .set(reviewCase.STATUS, newStatus.name)
        .set(reviewCase.REVISION, reviewCase.REVISION.plus(1L))
        .set(reviewCase.LAST_MODIFIED_DATE, now)
        .where(reviewCase.ID.eq(caseId))
        .and(reviewCase.REVISION.eq(expectedRevision))
        .execute()
    if (updated != 1) return false

    dslRW
      .insertInto(
        this.override,
        this.override.ID,
        this.override.TYPE,
        this.override.BOOK_LOW_ID,
        this.override.BOOK_HIGH_ID,
        this.override.BOOK_ID,
        this.override.LOW_CONTENT_GENERATION,
        this.override.HIGH_CONTENT_GENERATION,
        this.override.LOW_COVER_GENERATION,
        this.override.HIGH_COVER_GENERATION,
        this.override.ACTOR_ID,
        this.override.REASON,
        this.override.CREATED_DATE,
      ).values(
        override.id,
        override.type.name,
        override.bookLowId,
        override.bookHighId,
        override.bookId,
        override.lowContentGeneration,
        override.highContentGeneration,
        override.lowCoverGeneration,
        override.highCoverGeneration,
        override.actorId,
        override.reason,
        override.createdDate,
      ).execute()
    return true
  }

  override fun findProtectedBookIds(bookIds: Set<String>): Set<String> =
    if (bookIds.isEmpty()) {
      emptySet()
    } else {
      dslRO
        .select(this.override.BOOK_ID)
        .from(this.override)
        .where(this.override.TYPE.eq(DedupOverrideType.PROTECTED.name))
        .and(this.override.BOOK_ID.`in`(bookIds))
        .fetchSet(this.override.BOOK_ID)
        .filterNotNull()
        .toSet()
    }

  override fun findReviewCases(
    libraryId: String?,
    origin: DedupReviewCaseOrigin?,
  ): List<DedupReviewCase> {
    val records =
      dslRO
        .selectFrom(reviewCase)
        .where(libraryId?.let { reviewCase.LIBRARY_ID.eq(it) } ?: DSL.noCondition())
        .and(origin?.let { reviewCase.ORIGIN.eq(it.name) } ?: DSL.noCondition())
        .orderBy(reviewCase.LAST_MODIFIED_DATE.desc(), reviewCase.ID)
        .fetch()
    val members = findCaseMembers(records.mapNotNull { it.id }.toSet())
    return records.map { it.toDomain(members[it.id].orEmpty()) }
  }

  private fun findCaseMembers(caseIds: Set<String>): Map<String, Set<String>> =
    if (caseIds.isEmpty()) {
      emptyMap()
    } else {
      dslRO
        .select(reviewMember.CASE_ID, reviewMember.BOOK_ID)
        .from(reviewMember)
        .where(reviewMember.CASE_ID.`in`(caseIds))
        .fetchGroups(reviewMember.CASE_ID, reviewMember.BOOK_ID)
        .mapValues { it.value.toSet() }
    }

  @Transactional
  override fun insertDecision(
    decision: DedupDecision,
    items: Collection<DedupDecisionItem>,
  ) {
    require(items.isNotEmpty()) { "A dedup decision must contain at least one removal item" }
    require(items.all { it.decisionId == decision.id }) { "Decision items must belong to the decision" }
    dslRW
      .insertInto(
        this.decision,
        this.decision.ID,
        this.decision.REVIEW_CASE_ID,
        this.decision.PLAN_REVISION,
        this.decision.MODE,
        this.decision.KEEPER_BOOK_ID,
        this.decision.KEEPER_SNAPSHOT_JSON,
        this.decision.PLAN_JSON,
        this.decision.EVIDENCE_JSON,
        this.decision.ELIGIBILITY_JSON,
        this.decision.CLASSIFIER_RULE_VERSION,
        this.decision.MANUAL_CONFIRMATION_JSON,
        this.decision.STATE,
        this.decision.ACTOR_ID,
        this.decision.EXECUTION_TOKEN,
        this.decision.LEASE_UNTIL,
        this.decision.RESULT_JSON,
        this.decision.GORSE_SYNC_STATE,
        this.decision.REMOTE_CONFIRMATION_STATE,
        this.decision.APPROVED_DATE,
        this.decision.EXECUTED_DATE,
        this.decision.COMPLETED_DATE,
        this.decision.CREATED_DATE,
        this.decision.LAST_MODIFIED_DATE,
      ).values(
        decision.id,
        decision.reviewCaseId,
        decision.planRevision,
        decision.mode.name,
        decision.keeperBookId,
        decision.keeperSnapshotJson,
        decision.planJson,
        decision.evidenceJson,
        decision.eligibilityJson,
        decision.classifierRuleVersion,
        decision.manualConfirmationJson,
        decision.state.name,
        decision.actorId,
        decision.executionToken,
        decision.leaseUntil,
        decision.resultJson,
        decision.gorseSyncState,
        decision.remoteConfirmationState,
        decision.approvedDate,
        decision.executedDate,
        decision.completedDate,
        decision.createdDate,
        decision.lastModifiedDate,
      ).execute()

    items.forEach { item ->
      dslRW
        .insertInto(
          decisionItem,
          decisionItem.ID,
          decisionItem.DECISION_ID,
          decisionItem.BOOK_ID,
          decisionItem.SERIES_ID,
          decisionItem.LIBRARY_ID,
          decisionItem.TITLE_SNAPSHOT,
          decisionItem.PATH_SNAPSHOT,
          decisionItem.EXPECTED_PATH,
          decisionItem.EXPECTED_SIZE,
          decisionItem.EXPECTED_MTIME,
          decisionItem.EXPECTED_ARCHIVE_HASH,
          decisionItem.SOURCE_CONTENT_GENERATION,
          decisionItem.SERIES_SCOPE_REVISION,
          decisionItem.STATE_REVISION,
          decisionItem.ACKNOWLEDGED_REASONS_JSON,
          decisionItem.DIRECT_RELATION_ID,
          decisionItem.DIRECT_RELATION_GENERATIONS,
          decisionItem.STATE,
          decisionItem.ATTEMPT_COUNT,
          decisionItem.RESULT_CODE,
          decisionItem.RESULT_JSON,
          decisionItem.LAST_ERROR,
          decisionItem.STABILITY_NOT_BEFORE,
          decisionItem.DELETED_DATE,
          decisionItem.CREATED_DATE,
          decisionItem.LAST_MODIFIED_DATE,
        ).values(
          item.id,
          item.decisionId,
          item.bookId,
          item.seriesId,
          item.libraryId,
          item.titleSnapshot,
          item.pathSnapshot,
          item.expectedPath,
          item.expectedSize,
          item.expectedMtime,
          item.expectedArchiveHash,
          item.sourceContentGeneration,
          item.seriesScopeRevision,
          item.stateRevision,
          item.acknowledgedReasonsJson,
          item.directRelationId,
          item.directRelationGenerations,
          item.state.name,
          item.attemptCount,
          item.resultCode,
          item.resultJson,
          item.lastError,
          item.stabilityNotBefore,
          item.deletedDate,
          item.createdDate,
          item.lastModifiedDate,
        ).execute()
    }
  }

  override fun findDecision(decisionId: String): DedupDecision? =
    dslRO.selectFrom(decision).where(decision.ID.eq(decisionId)).fetchOne()?.let {
      DedupDecision(
        id = it.id!!,
        reviewCaseId = it.reviewCaseId,
        planRevision = it.planRevision!!,
        mode = DedupDecisionMode.valueOf(it.mode!!),
        keeperBookId = it.keeperBookId!!,
        keeperSnapshotJson = it.keeperSnapshotJson!!,
        planJson = it.planJson!!,
        evidenceJson = it.evidenceJson!!,
        eligibilityJson = it.eligibilityJson!!,
        classifierRuleVersion = it.classifierRuleVersion!!,
        manualConfirmationJson = it.manualConfirmationJson,
        state = DedupDecisionState.valueOf(it.state!!),
        actorId = it.actorId!!,
        executionToken = it.executionToken,
        leaseUntil = it.leaseUntil,
        resultJson = it.resultJson!!,
        gorseSyncState = it.gorseSyncState!!,
        remoteConfirmationState = it.remoteConfirmationState!!,
        approvedDate = it.approvedDate,
        executedDate = it.executedDate,
        completedDate = it.completedDate,
        createdDate = it.createdDate!!,
        lastModifiedDate = it.lastModifiedDate!!,
      )
    }

  override fun findAllDecisions(): List<DedupDecision> =
    dslRO
      .select(decision.ID)
      .from(decision)
      .orderBy(decision.CREATED_DATE.desc(), decision.ID.desc())
      .fetch(decision.ID)
      .mapNotNull(::findDecision)

  override fun findDecisionItems(decisionId: String): List<DedupDecisionItem> =
    dslRO
      .selectFrom(decisionItem)
      .where(decisionItem.DECISION_ID.eq(decisionId))
      .orderBy(decisionItem.CREATED_DATE, decisionItem.ID)
      .fetch { it ->
        DedupDecisionItem(
          id = it.id!!,
          decisionId = it.decisionId!!,
          bookId = it.bookId!!,
          seriesId = it.seriesId!!,
          libraryId = it.libraryId!!,
          titleSnapshot = it.titleSnapshot!!,
          pathSnapshot = it.pathSnapshot!!,
          expectedPath = it.expectedPath!!,
          expectedSize = it.expectedSize!!,
          expectedMtime = it.expectedMtime!!,
          expectedArchiveHash = it.expectedArchiveHash!!,
          sourceContentGeneration = it.sourceContentGeneration!!,
          seriesScopeRevision = it.seriesScopeRevision!!,
          stateRevision = it.stateRevision!!,
          acknowledgedReasonsJson = it.acknowledgedReasonsJson!!,
          directRelationId = it.directRelationId!!,
          directRelationGenerations = it.directRelationGenerations!!,
          state = DedupDecisionItemState.valueOf(it.state!!),
          attemptCount = it.attemptCount!!,
          resultCode = it.resultCode,
          resultJson = it.resultJson,
          lastError = it.lastError,
          stabilityNotBefore = it.stabilityNotBefore,
          deletedDate = it.deletedDate,
          createdDate = it.createdDate!!,
          lastModifiedDate = it.lastModifiedDate!!,
        )
      }

  override fun findDecisionItem(itemId: String): DedupDecisionItem? =
    dslRO.select(decisionItem.DECISION_ID).from(decisionItem).where(decisionItem.ID.eq(itemId)).fetchOne(decisionItem.DECISION_ID)?.let { decisionId ->
      findDecisionItems(decisionId).firstOrNull { it.id == itemId }
    }

  override fun findDecisionsByStates(states: Set<DedupDecisionState>): List<DedupDecision> =
    if (states.isEmpty()) {
      emptyList()
    } else {
      dslRO
        .select(decision.ID)
        .from(decision)
        .where(decision.STATE.`in`(states.map { it.name }))
        .orderBy(decision.LAST_MODIFIED_DATE, decision.ID)
        .fetch(decision.ID)
        .mapNotNull(::findDecision)
    }

  override fun hasActiveDecisionForBooks(bookIds: Set<String>): Boolean =
    bookIds.isNotEmpty() &&
      dslRO.fetchExists(
        DSL
          .selectOne()
          .from(decisionItem)
          .join(decision)
          .on(decision.ID.eq(decisionItem.DECISION_ID))
          .where(decisionItem.BOOK_ID.`in`(bookIds))
          .and(
            decision.STATE.notIn(
              DedupDecisionState.COMPLETED.name,
              DedupDecisionState.ABORTED.name,
              DedupDecisionState.FAILED.name,
              DedupDecisionState.REAPPROVAL_REQUIRED.name,
            ),
          ),
      )

  override fun countDecisionStates(): Map<DedupDecisionState, Int> =
    dslRO
      .select(decision.STATE, DSL.count())
      .from(decision)
      .groupBy(decision.STATE)
      .fetch()
      .associate { DedupDecisionState.valueOf(it.value1()) to it.value2() }

  override fun countDecisionItemStates(): Map<DedupDecisionItemState, Int> =
    dslRO
      .select(decisionItem.STATE, DSL.count())
      .from(decisionItem)
      .groupBy(decisionItem.STATE)
      .fetch()
      .associate { DedupDecisionItemState.valueOf(it.value1()) to it.value2() }

  override fun countGorseSyncStates(): Map<String, Int> =
    dslRO
      .select(gorseSync.STATE, DSL.count())
      .from(gorseSync)
      .groupBy(gorseSync.STATE)
      .fetch()
      .associate { it.value1() to it.value2() }

  override fun claimDecision(
    decisionId: String,
    expectedStates: Set<DedupDecisionState>,
    newState: DedupDecisionState,
    executionToken: String,
    leaseUntil: LocalDateTime,
    now: LocalDateTime,
  ): Boolean =
    dslRW
      .update(decision)
      .set(decision.STATE, newState.name)
      .set(decision.EXECUTION_TOKEN, executionToken)
      .set(decision.LEASE_UNTIL, leaseUntil)
      .set(decision.LAST_MODIFIED_DATE, now)
      .where(decision.ID.eq(decisionId))
      .and(decision.STATE.`in`(expectedStates.map { it.name }))
      .and(decision.EXECUTION_TOKEN.isNull.or(decision.LEASE_UNTIL.lt(now)))
      .execute() == 1

  override fun updateDecisionState(
    decisionId: String,
    executionToken: String,
    expectedStates: Set<DedupDecisionState>,
    newState: DedupDecisionState,
    resultJson: String,
    releaseLease: Boolean,
    now: LocalDateTime,
  ): Boolean {
    val update =
      dslRW
        .update(decision)
        .set(decision.STATE, newState.name)
        .set(decision.RESULT_JSON, resultJson)
        .set(decision.LAST_MODIFIED_DATE, now)
    if (newState == DedupDecisionState.PURGING) update.set(decision.EXECUTED_DATE, DSL.coalesce(decision.EXECUTED_DATE, now))
    if (newState == DedupDecisionState.COMPLETED) update.set(decision.COMPLETED_DATE, now)
    if (releaseLease) {
      update.set(decision.EXECUTION_TOKEN, null as String?)
      update.set(decision.LEASE_UNTIL, null as LocalDateTime?)
    }
    return update
      .where(decision.ID.eq(decisionId))
      .and(decision.STATE.`in`(expectedStates.map { it.name }))
      .and(decision.EXECUTION_TOKEN.eq(executionToken))
      .execute() == 1
  }

  override fun updateDecisionItem(
    itemId: String,
    decisionId: String,
    executionToken: String,
    expectedStates: Set<DedupDecisionItemState>,
    newState: DedupDecisionItemState,
    resultCode: String?,
    resultJson: String?,
    lastError: String?,
    stabilityNotBefore: LocalDateTime?,
    deletedDate: LocalDateTime?,
    incrementAttempt: Boolean,
    now: LocalDateTime,
  ): Boolean {
    val update =
      dslRW
        .update(decisionItem)
        .set(decisionItem.STATE, newState.name)
        .set(decisionItem.RESULT_CODE, resultCode)
        .set(decisionItem.RESULT_JSON, resultJson)
        .set(decisionItem.LAST_ERROR, lastError?.take(500))
        .set(decisionItem.STABILITY_NOT_BEFORE, stabilityNotBefore)
        .set(decisionItem.DELETED_DATE, deletedDate)
        .set(decisionItem.LAST_MODIFIED_DATE, now)
    if (incrementAttempt) update.set(decisionItem.ATTEMPT_COUNT, decisionItem.ATTEMPT_COUNT.plus(1))
    return update
      .where(decisionItem.ID.eq(itemId))
      .and(decisionItem.DECISION_ID.eq(decisionId))
      .and(decisionItem.STATE.`in`(expectedStates.map { it.name }))
      .andExists(
        DSL
          .selectOne()
          .from(decision)
          .where(decision.ID.eq(decisionId))
          .and(decision.EXECUTION_TOKEN.eq(executionToken)),
      ).execute() == 1
  }

  override fun releaseExpiredDecisionLeases(now: LocalDateTime): Int =
    dslRW
      .update(decision)
      .set(decision.STATE, DedupDecisionState.NEEDS_ATTENTION.name)
      .set(decision.EXECUTION_TOKEN, null as String?)
      .set(decision.LEASE_UNTIL, null as LocalDateTime?)
      .set(decision.RESULT_JSON, "{\"code\":\"LEASE_EXPIRED\"}")
      .set(decision.LAST_MODIFIED_DATE, now)
      .where(decision.STATE.`in`(DedupDecisionState.REVALIDATING.name, DedupDecisionState.PURGING.name))
      .and(decision.LEASE_UNTIL.lt(now))
      .execute()

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
      .set(gorseSync.ATTEMPT_COUNT, 0)
      .set(gorseSync.NEXT_RETRY_AT, null as LocalDateTime?)
      .set(gorseSync.LAST_ERROR, null as String?)
      .set(gorseSync.COMPLETED_DATE, null as LocalDateTime?)
      .set(gorseSync.LAST_MODIFIED_DATE, now)
      .execute()
  }

  @Transactional
  override fun findPendingGorseSync(now: LocalDateTime): DedupGorseSync? {
    val record =
      dslRW
        .selectFrom(gorseSync)
        .where(gorseSync.STATE.eq("PENDING"))
        .and(gorseSync.NEXT_RETRY_AT.isNull.or(gorseSync.NEXT_RETRY_AT.le(now)))
        .orderBy(gorseSync.LAST_MODIFIED_DATE, gorseSync.SERIES_ID)
        .limit(1)
        .fetchOne() ?: return null
    if (dslRW
        .update(gorseSync)
        .set(gorseSync.STATE, "RUNNING")
        .set(gorseSync.LAST_MODIFIED_DATE, now)
        .where(gorseSync.SERIES_ID.eq(record.seriesId))
        .and(gorseSync.STATE.eq("PENDING"))
        .execute() != 1
    )
      return null
    return record.toDomain().copy(state = "RUNNING", lastModifiedDate = now)
  }

  override fun findGorseSync(seriesId: String): DedupGorseSync? =
    dslRO
      .selectFrom(gorseSync)
      .where(gorseSync.SERIES_ID.eq(seriesId))
      .fetchOne()
      ?.toDomain()

  override fun completeGorseSync(
    seriesId: String,
    now: LocalDateTime,
  ): Boolean =
    dslRW
      .update(gorseSync)
      .set(gorseSync.STATE, "SUCCEEDED")
      .set(gorseSync.ATTEMPT_COUNT, 0)
      .set(gorseSync.NEXT_RETRY_AT, null as LocalDateTime?)
      .set(gorseSync.LAST_ERROR, null as String?)
      .set(gorseSync.COMPLETED_DATE, now)
      .set(gorseSync.LAST_MODIFIED_DATE, now)
      .where(gorseSync.SERIES_ID.eq(seriesId))
      .and(gorseSync.STATE.eq("RUNNING"))
      .execute() == 1

  @Transactional
  override fun failGorseSync(
    seriesId: String,
    error: String,
    now: LocalDateTime,
  ): Boolean {
    val current =
      dslRW
        .selectFrom(gorseSync)
        .where(gorseSync.SERIES_ID.eq(seriesId))
        .and(gorseSync.STATE.eq("RUNNING"))
        .fetchOne() ?: return false
    val attempts = current.attemptCount!! + 1
    val terminal = attempts >= 8
    val retryAt = if (terminal) null else now.plusSeconds(min(3_600L, 5L * (1L shl min(attempts - 1, 10))))
    return dslRW
      .update(gorseSync)
      .set(gorseSync.STATE, if (terminal) "FAILED_REVIEW" else "PENDING")
      .set(gorseSync.ATTEMPT_COUNT, attempts)
      .set(gorseSync.NEXT_RETRY_AT, retryAt)
      .set(gorseSync.LAST_ERROR, error.take(500))
      .set(gorseSync.LAST_MODIFIED_DATE, now)
      .where(gorseSync.SERIES_ID.eq(seriesId))
      .and(gorseSync.STATE.eq("RUNNING"))
      .execute() == 1
  }

  @Transactional
  override fun deleteAllDedupData() {
    dslRW.deleteFrom(gorseSync).execute()
    dslRW.deleteFrom(decisionItem).execute()
    dslRW.deleteFrom(decision).execute()
    dslRW.deleteFrom(override).execute()
    dslRW.deleteFrom(reviewMember).execute()
    dslRW.deleteFrom(reviewCase).execute()
    dslRW.deleteFrom(relation).execute()
    dslRW.deleteFrom(pageFeature).execute()
    dslRW.deleteFrom(feature).execute()
    dslRW.deleteFrom(work).execute()
    dslRW.deleteFrom(settings).execute()
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
      completionStabilitySeconds = completionStabilitySeconds!!,
      coverCandidateDistance = coverCandidateDistance!!,
      coverTopK = coverTopK!!,
      createdDate = createdDate!!,
      lastModifiedDate = lastModifiedDate!!,
    )

  private fun DedupWorkRecord.toDomain() =
    DedupWork(
      id = id!!,
      libraryId = libraryId!!,
      type = DedupWorkType.valueOf(type!!),
      targetKey = targetKey!!,
      state = DedupWorkState.valueOf(state!!),
      desiredRevision = desiredRevision!!,
      completedRevision = completedRevision!!,
      notBefore = notBefore!!,
      nextRetryAt = nextRetryAt,
      leaseOwner = leaseOwner,
      leaseToken = leaseToken,
      leaseUntil = leaseUntil,
      attemptCount = attemptCount!!,
      maxAttempts = maxAttempts!!,
      lastErrorCode = lastErrorCode,
      lastError = lastError,
      priority = priority!!,
      createdDate = createdDate!!,
      lastModifiedDate = lastModifiedDate!!,
      completedDate = completedDate,
    )

  private fun DedupFeatureRecord.toDomain() =
    DedupFeature(
      bookId = bookId!!,
      seriesId = seriesId!!,
      libraryId = libraryId!!,
      sourceContentGeneration = sourceContentGeneration!!,
      sourceCoverGeneration = sourceCoverGeneration!!,
      sourceMetadataGeneration = sourceMetadataGeneration!!,
      seriesScopeRevision = seriesScopeRevision!!,
      featureSchemaVersion = featureSchemaVersion!!,
      coverState = DedupFeatureState.valueOf(coverState!!),
      pageState = DedupFeatureState.valueOf(pageState!!),
      coverSource = coverSource,
      coverHash = coverHash,
      coverQuality = coverQuality,
      pageCount = pageCount,
      analyzedDate = analyzedDate,
      lastModifiedDate = lastModifiedDate!!,
    )

  private fun org.gotson.komga.jooq.main.tables.records.DedupGorseSyncRecord.toDomain() =
    DedupGorseSync(
      seriesId = seriesId!!,
      libraryId = libraryId!!,
      desiredHidden = desiredHidden!!,
      state = state!!,
      attemptCount = attemptCount!!,
      nextRetryAt = nextRetryAt,
      lastError = lastError,
      createdDate = createdDate!!,
      lastModifiedDate = lastModifiedDate!!,
      completedDate = completedDate,
    )

  private fun org.gotson.komga.jooq.main.tables.records.DedupReviewCaseRecord.toDomain(memberIds: Set<String>) =
    DedupReviewCase(
      id = id!!,
      libraryId = libraryId!!,
      revision = revision!!,
      status = DedupReviewCaseStatus.valueOf(status!!),
      suggestedKeeperBookId = suggestedKeeperBookId,
      origin = DedupReviewCaseOrigin.valueOf(origin!!),
      memberBookIds = memberIds,
      createdDate = createdDate!!,
      lastModifiedDate = lastModifiedDate!!,
    )
}
