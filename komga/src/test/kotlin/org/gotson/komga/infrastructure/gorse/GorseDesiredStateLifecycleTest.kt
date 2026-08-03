package org.gotson.komga.infrastructure.gorse

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookMetadataAggregationRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupDecisionRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class GorseDesiredStateLifecycleTest {
  private val decisionRepository = mockk<DedupDecisionRepository>()
  private val seriesRepository = mockk<SeriesRepository>()
  private val bookRepository = mockk<BookRepository>()
  private val seriesMetadataRepository = mockk<SeriesMetadataRepository>()
  private val aggregationRepository = mockk<BookMetadataAggregationRepository>()
  private val settings = mockk<GorseSettingsProvider>()
  private val client = mockk<GorseClient>()
  private val lifecycle =
    GorseDesiredStateLifecycle(
      decisionRepository,
      seriesRepository,
      bookRepository,
      seriesMetadataRepository,
      aggregationRepository,
      settings,
      client,
    )

  @Test
  fun `full item payload is hidden when a Series has no active Books`() {
    val series = makeSeries("empty", "library")
    every { bookRepository.findAllBySeriesId(series.id) } returns emptyList()
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns null
    every { aggregationRepository.findByIdOrNull(series.id) } returns null

    assertThat(lifecycle.buildItem(series).IsHidden).isTrue
  }

  @Test
  fun `full item payload is visible only while the Series and one of its Books are active`() {
    val series = makeSeries("active", "library")
    val active = makeBook("active", libraryId = series.libraryId, seriesId = series.id)
    val deleted = makeBook("deleted", libraryId = series.libraryId, seriesId = series.id).copy(deletedDate = LocalDateTime.now())
    every { bookRepository.findAllBySeriesId(series.id) } returns listOf(deleted, active)
    every { seriesMetadataRepository.findByIdOrNull(series.id) } returns null
    every { aggregationRepository.findByIdOrNull(series.id) } returns null

    assertThat(lifecycle.buildItem(series).IsHidden).isFalse
    assertThat(lifecycle.buildItem(series.copy(deletedDate = LocalDateTime.now())).IsHidden).isTrue
  }
}
