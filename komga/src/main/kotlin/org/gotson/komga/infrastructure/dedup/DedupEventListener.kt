package org.gotson.komga.infrastructure.dedup

import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.service.DedupWorkLifecycle
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class DedupEventListener(
  private val dedupWorkLifecycle: DedupWorkLifecycle,
  private val bookRepository: BookRepository,
) {
  @EventListener
  fun handleEvent(event: DomainEvent) {
    when (event) {
      is DomainEvent.BookAdded ->
        dedupWorkLifecycle.requestBookScan(event.book.libraryId, event.book.id, DedupWorkLifecycle.PRIORITY_ADDED)
      is DomainEvent.BookUpdated ->
        dedupWorkLifecycle.requestBookScan(event.book.libraryId, event.book.id, DedupWorkLifecycle.PRIORITY_UPDATED)
      is DomainEvent.BookDeleted ->
        dedupWorkLifecycle.requestBookScan(event.book.libraryId, event.book.id, DedupWorkLifecycle.PRIORITY_DELETED)
      is DomainEvent.ThumbnailBookAdded -> {
        val libraryId = bookRepository.getLibraryIdOrNull(event.thumbnail.bookId) ?: return
        dedupWorkLifecycle.requestBookScan(libraryId, event.thumbnail.bookId, DedupWorkLifecycle.PRIORITY_UPDATED)
      }
      is DomainEvent.ThumbnailBookDeleted -> {
        val libraryId = bookRepository.getLibraryIdOrNull(event.thumbnail.bookId) ?: return
        dedupWorkLifecycle.requestBookScan(libraryId, event.thumbnail.bookId, DedupWorkLifecycle.PRIORITY_UPDATED)
      }
      is DomainEvent.LibraryScanned -> dedupWorkLifecycle.requestLibraryBatch(event.library.id)
      else -> Unit
    }
  }
}
