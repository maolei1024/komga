package org.gotson.komga.interfaces.api.rest

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.infrastructure.gorse.GorseRecommendation
import org.gotson.komga.interfaces.api.rest.dto.BookMetadataAggregationDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesDto
import org.gotson.komga.interfaces.api.rest.dto.SeriesMetadataDto
import org.junit.jupiter.api.Test

class GorseRecommendationRankerTest {
  @Test
  fun `given square root penalty when ranking then lower tag count can outrank higher raw score`() {
    val fourTags = series(metadataTags = (1..4).map { "tag-$it" }.toSet())
    val sixteenTags = series(metadataTags = (1..16).map { "tag-$it" }.toSet())

    val result =
      GorseRecommendationRanker.rank(
        candidates =
          listOf(
            GorseRecommendation(Id = "sixteen", Score = 1.0) to sixteenTags,
            GorseRecommendation(Id = "four", Score = 0.8) to fourTags,
          ),
        tagPenaltyExponent = 0.5,
      )

    assertThat(result).containsExactly(fourTags, sixteenTags)
  }

  @Test
  fun `given duplicate and technical tags when counting then only unique content tags are included`() {
    val series =
      series(
        metadataTags = setOf("Romance", " tagSize_21-25 ", "pageSize_100-200"),
        bookMetadataTags = setOf("romance", "Action", "TAGSIZE_6-10", ""),
      )

    assertThat(GorseRecommendationRanker.countPenaltyTags(series)).isEqualTo(2)
  }

  @Test
  fun `given equal adjusted scores when ranking then original Gorse order is preserved`() {
    val first = series(metadataTags = setOf("one"))
    val second = series(metadataTags = setOf("one", "two", "three", "four"))

    val result =
      GorseRecommendationRanker.rank(
        candidates =
          listOf(
            GorseRecommendation(Id = "first", Score = 0.5) to first,
            GorseRecommendation(Id = "second", Score = 1.0) to second,
          ),
        tagPenaltyExponent = 0.5,
      )

    assertThat(result).containsExactly(first, second)
  }

  private fun series(
    metadataTags: Set<String> = emptySet(),
    bookMetadataTags: Set<String> = emptySet(),
  ): SeriesDto {
    val metadata = mockk<SeriesMetadataDto>()
    val booksMetadata = mockk<BookMetadataAggregationDto>()
    every { metadata.tags } returns metadataTags
    every { booksMetadata.tags } returns bookMetadataTags

    val series = mockk<SeriesDto>()
    every { series.metadata } returns metadata
    every { series.booksMetadata } returns booksMetadata
    return series
  }
}
