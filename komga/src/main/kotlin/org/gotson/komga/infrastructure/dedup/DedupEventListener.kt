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
    val libraryId =
      when (event) {
        is DomainEvent.BookAdded -> event.book.libraryId
        is DomainEvent.BookUpdated -> event.book.libraryId
        is DomainEvent.BookDeleted -> event.book.libraryId
        is DomainEvent.LibraryScanned -> event.library.id
        is DomainEvent.ThumbnailBookAdded -> bookRepository.getLibraryIdOrNull(event.thumbnail.bookId) ?: return
        is DomainEvent.ThumbnailBookDeleted -> bookRepository.getLibraryIdOrNull(event.thumbnail.bookId) ?: return
        else -> return
      }
    dedupWorkLifecycle.requestExactReconciliation(libraryId)
  }
}
