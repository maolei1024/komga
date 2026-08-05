package org.gotson.komga.domain.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupWork
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.gotson.komga.infrastructure.gorse.GorseDesiredStateLifecycle
import org.junit.jupiter.api.Test
import java.net.URL
import java.time.LocalDateTime

class DedupWorkLifecycleFailureTest {
  @Test
  fun `failed Book does not prevent the next claimed Book from completing`() {
    val repository = mockk<DedupRepository>(relaxed = true)
    val books = mockk<BookRepository>()
    val cover = mockk<DedupCoverLifecycle>(relaxed = true)
    val exact = mockk<DedupExactDuplicateLifecycle>(relaxed = true)
    val first = work("work-A", "A")
    val second = work("work-B", "B")
    val scanTypes = setOf(DedupWorkType.SCAN_BOOK)
    val internalTypes = setOf(DedupWorkType.VERIFY_RELATION, DedupWorkType.REBUILD_CLUSTERS)
    val lifecycle =
      DedupWorkLifecycle(
        repository,
        mockk<DedupResolutionRepository>(relaxed = true),
        books,
        exact,
        cover,
        mockk<DedupDeepVerificationLifecycle>(relaxed = true),
        mockk<DedupClusterLifecycle>(relaxed = true),
        mockk<TaskEmitter>(relaxed = true),
        mockk<GorseDesiredStateLifecycle>(relaxed = true),
      )
    every { repository.findLibrarySettings("library") } returns DedupLibrarySettings("library", enabled = true, batchSize = 2, quietPeriodSeconds = 0)
    every { repository.claimNextWork(any(), any(), "library", scanTypes, any()) } returnsMany listOf(first, second)
    every { repository.claimNextWork(any(), any(), "library", internalTypes, any()) } returns null
    every { books.findByIdOrNull("A") } returns book("A")
    every { books.findByIdOrNull("B") } returns book("B")
    every { cover.computeCover("A") } throws IllegalStateException("bad archive")
    every { cover.computeCover("B") } returns mockk<DedupFeature>()
    every { repository.completeWork(second.id, second.leaseToken!!, second.desiredRevision, any()) } returns true
    every { repository.updateLibraryBatchResult("library", 2, any()) } just Runs

    lifecycle.drain("library")

    verify(exactly = 1) { cover.computeCover("A") }
    verify(exactly = 1) { cover.computeCover("B") }
    verify(exactly = 1) { repository.failWork(first.id, first.leaseToken!!, first.desiredRevision, any(), match { it.contains("bad archive") }, any()) }
    verify(exactly = 1) { repository.completeWork(second.id, second.leaseToken!!, second.desiredRevision, any()) }
    verify(exactly = 1) { repository.updateLibraryBatchResult("library", 2, any()) }
  }

  private fun book(id: String) =
    Book(
      name = "$id.cbz",
      url = URL("file:/tmp/$id.cbz"),
      fileLastModified = LocalDateTime.MIN,
      fileSize = 100,
      id = id,
      seriesId = "series",
      libraryId = "library",
    )

  private fun work(
    id: String,
    bookId: String,
  ): DedupWork {
    val now = LocalDateTime.now()
    return DedupWork(
      id = id,
      libraryId = "library",
      type = DedupWorkType.SCAN_BOOK,
      targetKey = bookId,
      state = DedupWorkState.RUNNING,
      desiredRevision = 1,
      completedRevision = 0,
      notBefore = now,
      nextRetryAt = null,
      leaseOwner = "test",
      leaseToken = "lease-$id",
      leaseUntil = now.plusMinutes(10),
      attemptCount = 0,
      maxAttempts = 8,
      lastErrorCode = null,
      lastError = null,
      priority = 0,
      createdDate = now,
      lastModifiedDate = now,
      completedDate = null,
    )
  }
}
