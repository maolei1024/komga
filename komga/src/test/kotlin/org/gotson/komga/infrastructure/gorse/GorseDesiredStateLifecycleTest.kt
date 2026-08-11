package org.gotson.komga.infrastructure.gorse

import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupGorseSync
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookMetadataAggregationRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.LocalDateTime

class GorseDesiredStateLifecycleTest {
  private val dedupRepository = mockk<DedupRepository>()
  private val seriesRepository = mockk<SeriesRepository>()
  private val bookRepository = mockk<BookRepository>()
  private val seriesMetadataRepository = mockk<SeriesMetadataRepository>()
  private val aggregationRepository = mockk<BookMetadataAggregationRepository>()
  private val settings = mockk<GorseSettingsProvider>()
  private val client = mockk<GorseClient>()
  private val lifecycle = GorseDesiredStateLifecycle(dedupRepository, seriesRepository, bookRepository, seriesMetadataRepository, aggregationRepository, settings, client)

  @BeforeEach
  fun resetMocks() {
    clearMocks(dedupRepository, seriesRepository, bookRepository, seriesMetadataRepository, aggregationRepository, settings, client)
  }

  @Test
  fun `full item is hidden only when a Series has no active Books`() {
    val series = makeSeries("series", "library")
    val active = makeBook("active", libraryId = series.libraryId, seriesId = series.id)
    val deleted = makeBook("deleted", libraryId = series.libraryId, seriesId = series.id).copy(deletedDate = LocalDateTime.now())
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns null
    every { aggregationRepository.findByIdOrNull(series.id) } returns null
    every { bookRepository.findAllBySeriesId(series.id) } returns listOf(deleted, active) andThen listOf(deleted)

    assertThat(lifecycle.buildItem(series).IsHidden).isFalse()
    assertThat(lifecycle.buildItem(series).IsHidden).isTrue()
  }

  @Test
  fun `syncNow writes full payload reads IsHidden back and completes matching desired state`() {
    val now = LocalDateTime.now()
    val series = makeSeries("empty", "library")
    val work = DedupGorseSync(series.id, series.libraryId, true, "PENDING", 1, 0, null, null, now, now, null)
    every { settings.enabled } returns true
    every { seriesRepository.findByIdOrNull(series.id) } returns series
    every { bookRepository.findAllBySeriesId(series.id) } returns emptyList()
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns null
    every { aggregationRepository.findByIdOrNull(series.id) } returns null
    every { dedupRepository.enqueueGorseSync(series.id, series.libraryId, true, any()) } returns work
    every { client.upsertItemChecked(match { it.ItemId == series.id && it.IsHidden }) } just Runs
    every { client.getItemChecked(series.id) } returns GorseItem(series.id, true, Timestamp = "2026-08-04T00:00:00Z")
    every { dedupRepository.completeGorseSync(series.id, true, work.revision, any()) } returns true

    val result = lifecycle.syncNow(series.id)

    assertThat(result.state).isEqualTo(GorseSyncNowState.CONFIRMED)
    verify(exactly = 1) { client.upsertItemChecked(match { it.IsHidden }) }
    verify(exactly = 1) { client.getItemChecked(series.id) }
  }

  @Test
  fun `readback mismatch is persisted as failure`() {
    val now = LocalDateTime.now()
    val series = makeSeries("visible", "library")
    val work = DedupGorseSync(series.id, series.libraryId, true, "PENDING", 1, 0, null, null, now, now, null)
    every { settings.enabled } returns true
    every { seriesRepository.findByIdOrNull(series.id) } returns series
    every { bookRepository.findAllBySeriesId(series.id) } returns emptyList()
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns null
    every { aggregationRepository.findByIdOrNull(series.id) } returns null
    every { dedupRepository.enqueueGorseSync(series.id, series.libraryId, true, any()) } returns work
    every { client.upsertItemChecked(any()) } just Runs
    every { client.getItemChecked(series.id) } returns GorseItem(series.id, false, Timestamp = "2026-08-04T00:00:00Z")
    every { dedupRepository.failGorseSync(series.id, true, work.revision, any(), any()) } returns true

    assertThat(lifecycle.syncNow(series.id).state).isEqualTo(GorseSyncNowState.FAILED)
    verify(exactly = 1) { dedupRepository.failGorseSync(series.id, true, work.revision, match { it.contains("expected true") }, any()) }
  }

  @Test
  fun `missing deleted Series creates a hidden tombstone when Gorse Item is absent`() {
    val now = LocalDateTime.now()
    val seriesId = "missing"
    val libraryId = "library"
    val work = DedupGorseSync(seriesId, libraryId, true, "PENDING", 1, 0, null, null, now, now, null)
    val notFound =
      WebClientResponseException.create(
        HttpStatus.NOT_FOUND.value(),
        HttpStatus.NOT_FOUND.reasonPhrase,
        HttpHeaders.EMPTY,
        ByteArray(0),
        null,
      )
    every { settings.enabled } returns true
    every { seriesRepository.findByIdOrNull(seriesId) } returns null
    every { dedupRepository.enqueueGorseSync(seriesId, libraryId, true, any()) } returns work
    every { client.setHiddenChecked(seriesId, true) } just Runs
    every { client.getItemChecked(seriesId) } throws notFound andThen GorseItem(seriesId, true, Timestamp = "0001-01-01T00:00:00Z")
    every {
      client.upsertItemChecked(
        match {
          it.ItemId == seriesId &&
            it.IsHidden &&
            it.Categories == listOf(libraryId) &&
            it.Timestamp == "0001-01-01T00:00:00Z"
        },
      )
    } just Runs
    every { dedupRepository.completeGorseSync(seriesId, true, work.revision, any()) } returns true

    val result = lifecycle.syncNow(seriesId, libraryId)

    assertThat(result.state).isEqualTo(GorseSyncNowState.CONFIRMED)
    verify(exactly = 1) { client.setHiddenChecked(seriesId, true) }
    verify(exactly = 1) { client.upsertItemChecked(any()) }
    verify(exactly = 2) { client.getItemChecked(seriesId) }
    verify(exactly = 1) { dedupRepository.completeGorseSync(seriesId, true, work.revision, any()) }
  }

  @Test
  fun `reconcile persists remote failure for retry`() {
    val now = LocalDateTime.now()
    val series = makeSeries("pending", "library")
    val work = DedupGorseSync(series.id, series.libraryId, true, "RUNNING", 2, 0, null, null, now, now, null)
    every { settings.enabled } returns true
    every { dedupRepository.findPendingGorseSync(any()) } returns work
    every { seriesRepository.findByIdOrNull(series.id) } returns series
    every { bookRepository.findAllBySeriesId(series.id) } returns emptyList()
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns null
    every { aggregationRepository.findByIdOrNull(series.id) } returns null
    every { client.upsertItemChecked(any()) } throws IllegalStateException("Gorse unavailable")
    every { dedupRepository.failGorseSync(series.id, true, work.revision, any(), any()) } returns true

    assertThat(lifecycle.reconcile(1)).isEqualTo(1)

    verify(exactly = 1) { dedupRepository.failGorseSync(series.id, true, work.revision, match { it.contains("unavailable") }, any()) }
    verify(exactly = 0) { dedupRepository.completeGorseSync(any(), any(), any(), any()) }
  }

  @Test
  fun `disabled Gorse is not applicable and performs no persistence or network work`() {
    clearMocks(dedupRepository, client, answers = false)
    every { settings.enabled } returns false

    val result = lifecycle.syncNow("series", "library")

    assertThat(result.state).isEqualTo(GorseSyncNowState.NOT_APPLICABLE)
    verify(exactly = 0) { dedupRepository.enqueueGorseSync(any(), any(), any(), any()) }
    verify(exactly = 0) { client.upsertItemChecked(any()) }
    verify(exactly = 0) { client.getItemChecked(any()) }
  }
}
