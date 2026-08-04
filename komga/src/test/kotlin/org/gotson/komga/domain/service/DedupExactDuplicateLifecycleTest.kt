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
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.ExactDuplicateBookRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DedupExactDuplicateLifecycleTest {
  @Test
  fun `only equal hash and size CBZ books produce stable direct exact relations`() {
    val exactBooks = mockk<ExactDuplicateBookRepository>()
    val dedup = mockk<DedupRepository>()
    val lifecycle = DedupExactDuplicateLifecycle(exactBooks, dedup, jacksonObjectMapper())
    val books =
      listOf(
        book("A", "file:/A.cbz", "hash", 100),
        book("B", "file:/B.cbz", "hash", 100),
        book("C", "file:/C.cbz", "hash", 101),
        book("D", "file:/D.pdf", "hash", 100),
      )
    every { exactBooks.findAllExactDuplicates("library", false) } returns books
    val captured = slot<Collection<DedupRelation>>()
    every { dedup.replaceExactRelations("library", capture(captured), any()) } just Runs

    assertThat(lifecycle.reconcileLibrary("library")).isEqualTo(1)
    val first = captured.captured.single()
    assertThat(setOf(first.bookLowId, first.bookHighId)).isEqualTo(setOf("A", "B"))
    assertThat(first.type).isEqualTo(DedupRelationType.EXACT_FILE)

    lifecycle.reconcileLibrary("library")
    assertThat(captured.captured.single().id).isEqualTo(first.id)
  }

  private fun book(
    id: String,
    url: String,
    hash: String,
    size: Long,
  ) = ExactDuplicateBook(id, "series-$id", "library", id, url, hash, size, LocalDateTime.MIN, true, false)
}
