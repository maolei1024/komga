package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentSourceTag
import org.gotson.komga.domain.model.MetadataEnrichmentState
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.junit.jupiter.api.Test

class MetadataEnrichmentTagIndexTest {
  @Test
  fun `untranslated tags aggregate book counts and cache can be invalidated`() {
    val mapper = jacksonObjectMapper()
    val repository = mockk<MetadataEnrichmentStateRepository>()
    val dictionary = mockk<MetadataEnrichmentDictionaryService>()
    var states =
      listOf(
        state("book-1", listOf(MetadataEnrichmentSourceTag("tag", "missing"), MetadataEnrichmentSourceTag("artist", "known")), mapper),
        state("book-2", listOf(MetadataEnrichmentSourceTag("tag", "Missing")), mapper),
      )
    every { repository.findAllByProcessor(MetadataEnrichmentProcessor.TAG_TRANSLATION) } answers { states }
    every { dictionary.lookup(any(), any()) } answers {
      if (firstArg<String>() == "artist") "已翻译" else null
    }
    val index = MetadataEnrichmentTagIndex(repository, dictionary, mapper)

    assertThat(index.untranslated(null, null))
      .singleElement()
      .extracting("type", "value", "bookCount")
      .containsExactly("tag", "missing", 2)
    assertThat(index.bookIds("tag", "missing")).containsExactlyInAnyOrder("book-1", "book-2")

    states = emptyList()
    assertThat(index.untranslated(null, null)).hasSize(1)
    index.invalidate()
    assertThat(index.untranslated(null, null)).isEmpty()
  }

  private fun state(
    bookId: String,
    tags: List<MetadataEnrichmentSourceTag>,
    mapper: com.fasterxml.jackson.databind.ObjectMapper,
  ) = MetadataEnrichmentState(
    bookId = bookId,
    processor = MetadataEnrichmentProcessor.TAG_TRANSLATION,
    status = MetadataEnrichmentStatus.SUCCESS,
    revision = 1,
    inputHash = "hash",
    inputJson =
      mapper.writeValueAsString(
        MetadataEnrichmentSource(
          galleryId = 1,
          sourceRevision = "revision",
          originalTitle = "title",
          originalSeries = "series",
          summary = "summary",
          pageCount = 1,
          tags = tags,
          existingTags = emptySet(),
          legacyProcessed = false,
        ),
      ),
  )
}
