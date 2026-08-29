package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentState
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class MetadataEnrichmentStateDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  MetadataEnrichmentStateRepository {
  private val state = Tables.BOOK_ENRICHMENT_STATE
  private val book = Tables.BOOK

  override fun find(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
  ): MetadataEnrichmentState? =
    dslRO
      .selectFrom(state)
      .where(state.BOOK_ID.eq(bookId))
      .and(state.PROCESSOR.eq(processor.name))
      .fetchOne()
      ?.toDomain()

  override fun findAllByBookId(bookId: String): List<MetadataEnrichmentState> =
    dslRO
      .selectFrom(state)
      .where(state.BOOK_ID.eq(bookId))
      .fetch()
      .map { it.toDomain() }

  override fun findAllByProcessor(processor: MetadataEnrichmentProcessor): List<MetadataEnrichmentState> =
    dslRO
      .selectFrom(state)
      .where(state.PROCESSOR.eq(processor.name))
      .fetch()
      .map { it.toDomain() }

  override fun findAllByStatus(status: MetadataEnrichmentStatus): List<MetadataEnrichmentState> =
    dslRO
      .selectFrom(state)
      .where(state.STATUS.eq(status.name))
      .fetch()
      .map { it.toDomain() }

  override fun findAll(
    processor: MetadataEnrichmentProcessor?,
    status: MetadataEnrichmentStatus?,
    libraryId: String?,
    pageable: Pageable,
  ): Page<MetadataEnrichmentState> {
    val conditions = mutableListOf<Condition>()
    processor?.let { conditions += state.PROCESSOR.eq(it.name) }
    status?.let { conditions += state.STATUS.eq(it.name) }
    libraryId?.takeIf { it.isNotBlank() }?.let { conditions += book.LIBRARY_ID.eq(it) }
    val condition = conditions.fold(DSL.trueCondition(), Condition::and)

    val count =
      dslRO
        .selectCount()
        .from(state)
        .join(book)
        .on(book.ID.eq(state.BOOK_ID))
        .where(condition)
        .fetchOne(0, Long::class.java) ?: 0L

    val items =
      dslRO
        .select(state.asterisk())
        .from(state)
        .join(book)
        .on(book.ID.eq(state.BOOK_ID))
        .where(condition)
        .orderBy(state.LAST_MODIFIED_DATE.desc(), state.BOOK_ID.asc(), state.PROCESSOR.asc())
        .apply { if (pageable.isPaged) limit(pageable.pageSize).offset(pageable.offset) }
        .fetch()
        .map { it.toDomain() }

    return PageImpl(
      items,
      if (pageable.isPaged) PageRequest.of(pageable.pageNumber, pageable.pageSize) else PageRequest.of(0, maxOf(count.toInt(), 20)),
      count,
    )
  }

  override fun countByProcessorAndStatus(): Map<Pair<MetadataEnrichmentProcessor, MetadataEnrichmentStatus>, Long> =
    dslRO
      .select(state.PROCESSOR, state.STATUS, DSL.count())
      .from(state)
      .groupBy(state.PROCESSOR, state.STATUS)
      .fetch()
      .associate {
        (MetadataEnrichmentProcessor.valueOf(it.value1()) to MetadataEnrichmentStatus.valueOf(it.value2())) to it.value3().toLong()
      }

  override fun save(state: MetadataEnrichmentState) {
    state.toQuery(dslRW).execute()
  }

  override fun save(states: Collection<MetadataEnrichmentState>) {
    if (states.isNotEmpty()) dslRW.batch(states.map { it.toQuery(dslRW) }).execute()
  }

  @Transactional
  override fun markRunning(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    revision: Long,
  ): Boolean {
    val now = now()
    return dslRW
      .update(state)
      .set(state.STATUS, MetadataEnrichmentStatus.RUNNING.name)
      .set(state.STARTED_DATE, now)
      .set(state.LAST_ERROR, null as String?)
      .set(state.LAST_MODIFIED_DATE, now)
      .where(state.BOOK_ID.eq(bookId))
      .and(state.PROCESSOR.eq(processor.name))
      .and(state.REVISION.eq(revision))
      .and(state.STATUS.eq(MetadataEnrichmentStatus.WAITING.name))
      .execute() == 1
  }

  @Transactional
  override fun markStale(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    revision: Long,
  ): Boolean {
    val now = now()
    return dslRW
      .update(state)
      .set(state.STATUS, MetadataEnrichmentStatus.STALE.name)
      .set(state.STARTED_DATE, null as LocalDateTime?)
      .set(state.LAST_MODIFIED_DATE, now)
      .where(state.BOOK_ID.eq(bookId))
      .and(state.PROCESSOR.eq(processor.name))
      .and(state.REVISION.eq(revision))
      .and(state.STATUS.eq(MetadataEnrichmentStatus.WAITING.name))
      .execute() == 1
  }

  @Transactional
  override fun markSuccess(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    revision: Long,
    resultJson: String,
  ): Boolean {
    val now = now()
    return dslRW
      .update(state)
      .set(state.STATUS, MetadataEnrichmentStatus.SUCCESS.name)
      .set(state.RESULT_JSON, resultJson)
      .set(state.RESULT_REVISION, revision)
      .set(state.LAST_ERROR, null as String?)
      .set(state.COMPLETED_DATE, now)
      .set(state.LAST_MODIFIED_DATE, now)
      .where(state.BOOK_ID.eq(bookId))
      .and(state.PROCESSOR.eq(processor.name))
      .and(state.REVISION.eq(revision))
      .and(state.STATUS.eq(MetadataEnrichmentStatus.RUNNING.name))
      .execute() == 1
  }

  @Transactional
  override fun markFailure(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    revision: Long,
    error: String,
  ): Boolean {
    val now = now()
    return dslRW
      .update(state)
      .set(state.STATUS, MetadataEnrichmentStatus.FAILED.name)
      .set(state.LAST_ERROR, error.take(4000))
      .set(state.COMPLETED_DATE, now)
      .set(state.LAST_MODIFIED_DATE, now)
      .where(state.BOOK_ID.eq(bookId))
      .and(state.PROCESSOR.eq(processor.name))
      .and(state.REVISION.eq(revision))
      .and(state.STATUS.eq(MetadataEnrichmentStatus.RUNNING.name))
      .execute() == 1
  }

  @Transactional
  override fun resetRunning(): List<MetadataEnrichmentState> {
    val running = findAllByStatus(MetadataEnrichmentStatus.RUNNING)
    if (running.isEmpty()) return emptyList()
    val now = now()
    dslRW
      .update(state)
      .set(state.STATUS, MetadataEnrichmentStatus.WAITING.name)
      .set(state.STARTED_DATE, null as LocalDateTime?)
      .set(state.LAST_MODIFIED_DATE, now)
      .where(state.STATUS.eq(MetadataEnrichmentStatus.RUNNING.name))
      .execute()
    return running.map { it.copy(status = MetadataEnrichmentStatus.WAITING, startedDate = null, lastModifiedDate = now) }
  }

  override fun deleteByBookId(bookId: String): Int = dslRW.deleteFrom(state).where(state.BOOK_ID.eq(bookId)).execute()

  private fun MetadataEnrichmentState.toQuery(dsl: DSLContext) =
    dsl
      .insertInto(state)
      .set(state.BOOK_ID, bookId)
      .set(state.PROCESSOR, processor.name)
      .set(state.STATUS, status.name)
      .set(state.REVISION, revision)
      .set(state.INPUT_HASH, inputHash)
      .set(state.INPUT_JSON, inputJson)
      .set(state.RESULT_JSON, resultJson)
      .set(state.RESULT_REVISION, resultRevision)
      .set(state.LAST_ERROR, lastError)
      .set(state.STARTED_DATE, startedDate)
      .set(state.COMPLETED_DATE, completedDate)
      .set(state.CREATED_DATE, createdDate)
      .set(state.LAST_MODIFIED_DATE, lastModifiedDate)
      .onDuplicateKeyUpdate()
      .set(state.STATUS, status.name)
      .set(state.REVISION, revision)
      .set(state.INPUT_HASH, inputHash)
      .set(state.INPUT_JSON, inputJson)
      .set(state.RESULT_JSON, resultJson)
      .set(state.RESULT_REVISION, resultRevision)
      .set(state.LAST_ERROR, lastError)
      .set(state.STARTED_DATE, startedDate)
      .set(state.COMPLETED_DATE, completedDate)
      .set(state.LAST_MODIFIED_DATE, lastModifiedDate)

  private fun Record.toDomain() =
    MetadataEnrichmentState(
      bookId = get(state.BOOK_ID)!!,
      processor = MetadataEnrichmentProcessor.valueOf(get(state.PROCESSOR)!!),
      status = MetadataEnrichmentStatus.valueOf(get(state.STATUS)!!),
      revision = get(state.REVISION)!!,
      inputHash = get(state.INPUT_HASH)!!,
      inputJson = get(state.INPUT_JSON)!!,
      resultJson = get(state.RESULT_JSON),
      resultRevision = get(state.RESULT_REVISION),
      lastError = get(state.LAST_ERROR),
      startedDate = get(state.STARTED_DATE),
      completedDate = get(state.COMPLETED_DATE),
      createdDate = get(state.CREATED_DATE)!!,
      lastModifiedDate = get(state.LAST_MODIFIED_DATE)!!,
    )

  private fun now(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
}
