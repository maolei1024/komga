package org.gotson.komga.interfaces.api.rest

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.interfaces.api.rest.dto.BookMetadataAggregationDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesMetadataDto
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class GorseRecommendationMetadataTest {
  private val books = mockk<BookRepository>()
  private val media = mockk<MediaRepository>()
  private val service = GorseRecommendationMetadata(books, media)

  @Test
  fun `metadata aggregates active books in bulk and preserves unknown totals`() {
    val first = makeBook("first", seriesId = "multi")
    val second = makeBook("second", seriesId = "multi")
    val deleted = makeBook("deleted", seriesId = "multi").copy(deletedDate = LocalDateTime.now())
    val single = makeBook("single", seriesId = "single")
    val unknown = makeBook("unknown", seriesId = "unknown")
    every { books.findAllBySeriesIds(listOf("multi", "single", "unknown")) } returns listOf(first, second, deleted, single, unknown)
    every { media.getPagesSizes(listOf(first.id, second.id, single.id, unknown.id)) } returns
      listOf(first.id to 12, second.id to 24, single.id to 50)

    val result = service.enrich(listOf(series("multi"), series("single"), series("unknown")))

    assertThat(result.map { it.pagesCount }).containsExactly(36L, 50L, null)
    assertThat(result.map { it.tagCount }).containsOnly(2)
    verify(exactly = 1) { books.findAllBySeriesIds(any()) }
    verify(exactly = 1) { media.getPagesSizes(any()) }
  }

  private fun series(id: String): SeriesDto {
    val metadata = mockk<SeriesMetadataDto>()
    val aggregation = mockk<BookMetadataAggregationDto>()
    every { metadata.tags } returns setOf(" Romance ", "tagSize_20", "pageSize_50", "")
    every { aggregation.tags } returns setOf("romance", "Action", "TAGSIZE_100")
    val now = LocalDateTime.now()
    return SeriesDto(id, "library", id, "", now, now, now, 1, 0, 1, 0, metadata, aggregation, false, false)
  }
}
