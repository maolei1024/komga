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
        b.FILE_LAST_MODIFIED,
        b.ONESHOT,
        b.DELETED_DATE,
      ).from(b)
      .where(DSL.row(b.FILE_HASH, b.FILE_SIZE).`in`(duplicateIdentities))
      .and(condition)
      .orderBy(b.FILE_HASH, b.FILE_SIZE, b.ID)
      .fetch {
        ExactDuplicateBook(
          id = it[b.ID]!!,
          seriesId = it[b.SERIES_ID]!!,
          libraryId = it[b.LIBRARY_ID]!!,
          name = it[b.NAME]!!,
          url = it[b.URL]!!,
          fileHash = it[b.FILE_HASH]!!,
          fileSize = it[b.FILE_SIZE]!!,
          fileLastModified = it[b.FILE_LAST_MODIFIED]!!,
          oneshot = it[b.ONESHOT]!!,
          deleted = it[b.DELETED_DATE] != null,
        )
      }
  }
}
