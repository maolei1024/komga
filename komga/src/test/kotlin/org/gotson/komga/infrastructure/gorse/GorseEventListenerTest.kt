package org.gotson.komga.infrastructure.gorse

import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThatCode
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookMetadataAggregationRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GorseEventListenerTest {
  private val settings = mockk<GorseSettingsProvider>()
  private val desiredStateLifecycle = mockk<GorseDesiredStateLifecycle>()
  private val listener =
    GorseEventListener(
      mockk<GorseClient>(relaxed = true),
      settings,
      mockk<SeriesMetadataRepository>(relaxed = true),
      mockk<BookMetadataAggregationRepository>(relaxed = true),
      mockk<SeriesRepository>(relaxed = true),
      mockk<BookRepository>(relaxed = true),
      mockk<KomgaUserRepository>(relaxed = true),
      mockk<ReadProgressRepository>(relaxed = true),
      mockk<MediaRepository>(relaxed = true),
      desiredStateLifecycle,
    )

  @BeforeEach
  fun resetMocks() {
    clearMocks(settings, desiredStateLifecycle)
  }

  @Test
  fun `BookUpdated enqueues and reconciles Gorse desired state`() {
    val book = makeBook("book", libraryId = "library", seriesId = "series")
    every { settings.enabled } returns true
    every { desiredStateLifecycle.enqueue(book.seriesId, book.libraryId) } just Runs
    every { desiredStateLifecycle.reconcile(1) } returns 1

    listener.handleEvent(DomainEvent.BookUpdated(book))

    verifyOrder {
      desiredStateLifecycle.enqueue(book.seriesId, book.libraryId)
      desiredStateLifecycle.reconcile(1)
    }
  }

  @Test
  fun `Gorse failure after BookUpdated is contained`() {
    val book = makeBook("book", libraryId = "library", seriesId = "series")
    every { settings.enabled } returns true
    every { desiredStateLifecycle.enqueue(book.seriesId, book.libraryId) } just Runs
    every { desiredStateLifecycle.reconcile(1) } throws IllegalStateException("Gorse unavailable")

    assertThatCode { listener.handleEvent(DomainEvent.BookUpdated(book)) }.doesNotThrowAnyException()

    verifyOrder {
      desiredStateLifecycle.enqueue(book.seriesId, book.libraryId)
      desiredStateLifecycle.reconcile(1)
    }
  }
}
