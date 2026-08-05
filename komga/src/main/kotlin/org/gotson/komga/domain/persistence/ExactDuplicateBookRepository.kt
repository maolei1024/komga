package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.ExactDuplicateBook

interface ExactDuplicateBookRepository {
  /**
   * The single exact-file duplicate query used by both the existing books endpoint and dedup.
   * A duplicate identity is the pair of a non-empty file hash and file size.
   */
  fun findAllExactDuplicates(
    libraryId: String? = null,
    includeDeleted: Boolean = true,
  ): List<ExactDuplicateBook>

  /** Returns the active Books sharing the target Book's non-empty file hash and size. */
  fun findExactDuplicatesForBook(bookId: String): List<ExactDuplicateBook>
}
