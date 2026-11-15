package org.gotson.komga.infrastructure.gorse

import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDateTime
import java.util.TimeZone

@ResourceLock("default-time-zone")
class GorseEventListenerTest {
  private val settings = mockk<GorseSettingsProvider>()
  private val desiredStateLifecycle = mockk<GorseDesiredStateLifecycle>()
  private val client = mockk<GorseClient>(relaxed = true)
  private val books = mockk<BookRepository>(relaxed = true)
  private val progresses = mockk<ReadProgressRepository>(relaxed = true)
  private val media = mockk<MediaRepository>(relaxed = true)
  private val listener =
    GorseEventListener(
      client,
      settings,
      mockk<SeriesRepository>(relaxed = true),
      books,
      mockk<KomgaUserRepository>(relaxed = true),
      progresses,
      media,
      desiredStateLifecycle,
    )

  @BeforeEach
  fun resetMocks() {
    clearMocks(settings, desiredStateLifecycle, client, books, progresses, media)
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

  @ParameterizedTest
  @CsvSource(
    "Asia/Shanghai,2026-11-15T08:50:03,2026-11-15T00:50:03Z,14,27",
    "Asia/Shanghai,2026-11-15T09:23:57,2026-11-15T01:23:57Z,2,2",
    "Asia/Shanghai,2026-11-15T00:01:00,2026-11-14T16:01:00Z,1,1",
    "UTC,2026-11-15T08:50:03,2026-11-15T08:50:03Z,1,1",
    "America/New_York,2026-11-15T08:50:03,2026-11-15T13:50:03Z,1,1",
  )
  fun `events and bulk sync preserve the instant of local reading progress`(
    zone: String,
    localTime: String,
    expected: String,
    page: Int,
    pages: Int,
  ) {
    val originalZone = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone(zone))
      val book = makeBook("book", seriesId = "series")
      val progress = ReadProgress(book.id, "reader", page, page == pages, LocalDateTime.parse(localTime))
      val deliveries = mutableListOf<List<GorseFeedback>>()
      every { settings.enabled } returns true
      every { settings.readThreshold } returns 0.5
      every { settings.feedbackType } returns "read"
      every { books.findByIdOrNull(book.id) } returns book
      every { media.findByIdOrNull(book.id) } returns Media(bookId = book.id, pageCount = pages)
      every { progresses.findAll() } returns listOf(progress)
      every { client.insertFeedback(capture(deliveries)) } just Runs

      listener.handleEvent(DomainEvent.ReadProgressChanged(progress))
      assertThat(listener.syncAllFeedback()).isEqualTo(1)

      assertThat(deliveries.flatten().map { it.Timestamp }).containsExactly(expected, expected)
      assertThat(deliveries.flatten().map { it.FeedbackType }).containsOnly("read")
      assertThat(deliveries.flatten().map { it.ItemId }).containsOnly("series")
      assertThat(deliveries.flatten().map { it.UserId }).containsOnly("reader")
    } finally {
      TimeZone.setDefault(originalZone)
    }
  }
}
