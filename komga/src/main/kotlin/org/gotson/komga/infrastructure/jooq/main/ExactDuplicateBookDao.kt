package org.gotson.komga.infrastructure.jooq.main

import org.gotson.komga.domain.model.ExactDuplicateBook
import org.gotson.komga.domain.persistence.ExactDuplicateBookRepository
import org.gotson.komga.infrastructure.jooq.SplitDslDaoBase
import org.gotson.komga.jooq.main.Tables
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class ExactDuplicateBookDao(
  dslRW: DSLContext,
  @Qualifier("dslContextRO") dslRO: DSLContext,
) : SplitDslDaoBase(dslRW, dslRO),
  ExactDuplicateBookRepository {
  private val b = Tables.BOOK

  override fun findAllExactDuplicates(
    libraryId: String?,
    includeDeleted: Boolean,
  ): List<ExactDuplicateBook> {
    val condition =
      b.FILE_HASH
        .ne("")
        .and(libraryId?.let { b.LIBRARY_ID.eq(it) } ?: DSL.noCondition())
        .and(if (includeDeleted) DSL.noCondition() else b.DELETED_DATE.isNull)

    val duplicateIdentities =
      dslRO
        .select(b.FILE_HASH, b.FILE_SIZE)
        .from(b)
        .where(condition)
        .groupBy(b.FILE_HASH, b.FILE_SIZE)
        .having(DSL.count(b.ID).gt(1))

    return dslRO
      .select(
        b.ID,
        b.SERIES_ID,
        b.LIBRARY_ID,
        b.NAME,
        b.URL,
        b.FILE_HASH,
        b.FILE_SIZE,
        b.ONESHOT,
        b.DELETED_DATE,
      ).from(b)
      .where(DSL.row(b.FILE_HASH, b.FILE_SIZE).`in`(duplicateIdentities))
      .and(condition)
      .orderBy(b.FILE_HASH, b.FILE_SIZE, b.ID)
      .fetch(::toDomain)
  }

  override fun findExactDuplicatesForBook(bookId: String): List<ExactDuplicateBook> {
    val target =
      dslRO
        .select(b.FILE_HASH, b.FILE_SIZE, b.LIBRARY_ID)
        .from(b)
        .where(b.ID.eq(bookId))
        .and(b.DELETED_DATE.isNull)
        .fetchOne() ?: return emptyList()
    val hash = target[b.FILE_HASH].orEmpty()
    if (hash.isBlank()) return emptyList()
    return dslRO
      .select(
        b.ID,
        b.SERIES_ID,
        b.LIBRARY_ID,
        b.NAME,
        b.URL,
        b.FILE_HASH,
        b.FILE_SIZE,
        b.ONESHOT,
        b.DELETED_DATE,
      ).from(b)
      .where(b.FILE_HASH.eq(hash))
      .and(b.FILE_SIZE.eq(target[b.FILE_SIZE]))
      .and(b.LIBRARY_ID.eq(target[b.LIBRARY_ID]))
      .and(b.DELETED_DATE.isNull)
      .orderBy(b.ID)
      .fetch(::toDomain)
  }

  private fun toDomain(record: org.jooq.Record): ExactDuplicateBook =
    ExactDuplicateBook(
      id = record[b.ID]!!,
      seriesId = record[b.SERIES_ID]!!,
      libraryId = record[b.LIBRARY_ID]!!,
      name = record[b.NAME]!!,
      url = record[b.URL]!!,
      fileHash = record[b.FILE_HASH]!!,
      fileSize = record[b.FILE_SIZE]!!,
      oneshot = record[b.ONESHOT]!!,
      deleted = record[b.DELETED_DATE] != null,
    )
}
