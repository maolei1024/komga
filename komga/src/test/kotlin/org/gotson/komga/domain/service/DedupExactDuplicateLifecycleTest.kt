package org.gotson.komga.domain.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.ExactDuplicateBook
import org.gotson.komga.domain.model.dedupContentGeneration
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.ExactDuplicateBookRepository
import org.junit.jupiter.api.Test

class DedupExactDuplicateLifecycleTest {
  @Test
  fun `Book refresh only replaces exact relations touching its target`() {
    val exactBooks = mockk<ExactDuplicateBookRepository>()
    val dedup = mockk<DedupRepository>()
    val cover = mockk<DedupCoverLifecycle>()
    val lifecycle = DedupExactDuplicateLifecycle(exactBooks, dedup, cover, jacksonObjectMapper())
    val books =
      listOf(
        book("A", "file:/A.cbz", "hash", 100),
        book("B", "file:/B.cbz", "hash", 100),
        book("D", "file:/D.pdf", "hash", 100),
      )
    every { exactBooks.findExactDuplicatesForBook("A") } returns books
    books.forEach { every { cover.currentContentGeneration(it.id) } returns dedupContentGeneration(it.fileSize, null, it.fileHash) }
    val captured = slot<Collection<DedupRelation>>()
    every { dedup.replaceExactRelationsForBook("A", capture(captured), any()) } just Runs

    assertThat(lifecycle.refreshForBook("A")).isEqualTo(1)
    val first = captured.captured.single()
    assertThat(setOf(first.bookLowId, first.bookHighId)).isEqualTo(setOf("A", "B"))
    assertThat(first.type).isEqualTo(DedupRelationType.EXACT_FILE)

    lifecycle.refreshForBook("A")
    assertThat(captured.captured.single().id).isEqualTo(first.id)
  }

  private fun book(
    id: String,
    url: String,
    hash: String,
    size: Long,
  ) = ExactDuplicateBook(id, "series-$id", "library", id, url, hash, size, true, false)
}
