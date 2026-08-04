package org.gotson.komga.infrastructure.gorse

import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookMetadataAggregationRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.junit.jupiter.api.Test
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
    val series = makeSeries("empty", "library")
    every { settings.enabled } returns true
    every { seriesRepository.findByIdOrNull(series.id) } returns series
    every { bookRepository.findAllBySeriesId(series.id) } returns emptyList()
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns null
    every { aggregationRepository.findByIdOrNull(series.id) } returns null
    every { dedupRepository.enqueueGorseSync(series.id, series.libraryId, true, any()) } just Runs
    every { client.upsertItemChecked(match { it.ItemId == series.id && it.IsHidden }) } just Runs
    every { client.getItemChecked(series.id) } returns GorseItem(series.id, true, Timestamp = "2026-08-04T00:00:00Z")
    every { dedupRepository.completeGorseSync(series.id, true, any()) } returns true

    val result = lifecycle.syncNow(series.id)

    assertThat(result.state).isEqualTo(GorseSyncNowState.CONFIRMED)
    verify(exactly = 1) { client.upsertItemChecked(match { it.IsHidden }) }
    verify(exactly = 1) { client.getItemChecked(series.id) }
  }

  @Test
  fun `readback mismatch is persisted as failure`() {
    val series = makeSeries("visible", "library")
    every { settings.enabled } returns true
    every { seriesRepository.findByIdOrNull(series.id) } returns series
    every { bookRepository.findAllBySeriesId(series.id) } returns emptyList()
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns null
    every { aggregationRepository.findByIdOrNull(series.id) } returns null
    every { dedupRepository.enqueueGorseSync(series.id, series.libraryId, true, any()) } just Runs
    every { client.upsertItemChecked(any()) } just Runs
    every { client.getItemChecked(series.id) } returns GorseItem(series.id, false, Timestamp = "2026-08-04T00:00:00Z")
    every { dedupRepository.failGorseSync(series.id, true, any(), any()) } returns true

    assertThat(lifecycle.syncNow(series.id).state).isEqualTo(GorseSyncNowState.FAILED)
    verify(exactly = 1) { dedupRepository.failGorseSync(series.id, true, match { it.contains("expected true") }, any()) }
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
