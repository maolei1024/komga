package org.gotson.komga.application.tasks

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.BookMetadataPatchCapability
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.junit.jupiter.api.Test

class MetadataEnrichmentTaskTest {
  @Test
  fun `AI tasks share one global group while tag writers serialize per book`() {
    val aiOne = Task.EnrichMetadata("book-1", MetadataEnrichmentProcessor.AI_TITLE, 1)
    val aiTwo = Task.EnrichMetadata("book-2", MetadataEnrichmentProcessor.AI_TITLE, 1)
    val translation = Task.EnrichMetadata("book-1", MetadataEnrichmentProcessor.TAG_TRANSLATION, 1)
    val pageSize = Task.EnrichMetadata("book-1", MetadataEnrichmentProcessor.PAGE_SIZE, 1)
    val otherBook = Task.EnrichMetadata("book-2", MetadataEnrichmentProcessor.TAG_SIZE, 1)

    assertThat(aiOne.groupId).isEqualTo(aiTwo.groupId).isEqualTo("METADATA_ENRICHMENT_AI")
    assertThat(translation.groupId).isEqualTo(pageSize.groupId)
    assertThat(translation.groupId).isNotEqualTo(otherBook.groupId)
  }

  @Test
  fun `task identity contains revision so a newer trigger is not lost`() {
    val old = Task.EnrichMetadata("book", MetadataEnrichmentProcessor.TAG_TRANSLATION, 1)
    val newer = Task.EnrichMetadata("book", MetadataEnrichmentProcessor.TAG_TRANSLATION, 2)

    assertThat(old.uniqueId).isNotEqualTo(newer.uniqueId)
  }

  @Test
  fun `metadata refreshes triggered by enrichment keep distinct wake ups`() {
    val title =
      Task.RefreshBookMetadata(
        "book",
        setOf(BookMetadataPatchCapability.TITLE),
        groupId = "series",
        requestId = "METADATA_ENRICHMENT_AI_TITLE_1",
      )
    val tags =
      Task.RefreshBookMetadata(
        "book",
        setOf(BookMetadataPatchCapability.TAGS),
        groupId = "series",
        requestId = "METADATA_ENRICHMENT_TAG_TRANSLATION_1",
      )

    assertThat(title.uniqueId).isNotEqualTo(tags.uniqueId)
    assertThat(Task.RefreshBookMetadata("book", emptySet(), groupId = "series").uniqueId)
      .isEqualTo("REFRESH_BOOK_METADATA_book")
  }

  @Test
  fun `legacy metadata refresh payload without request id remains readable`() {
    val mapper = jacksonObjectMapper()
    val task = Task.RefreshBookMetadata("book", setOf(BookMetadataPatchCapability.TITLE), groupId = "series")
    val legacyPayload =
      mapper
        .valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(task)
        .apply { remove("requestId") }
        .let { mapper.writeValueAsString(it) }

    val restored = mapper.readValue(legacyPayload, Task.RefreshBookMetadata::class.java)

    assertThat(restored.requestId).isNull()
    assertThat(restored.uniqueId).isEqualTo("REFRESH_BOOK_METADATA_book")
  }

  @Test
  fun `persistent enrichment task round trips through the task JSON format`() {
    val mapper = jacksonObjectMapper()
    val task = Task.EnrichMetadata("book", MetadataEnrichmentProcessor.TAG_TRANSLATION, 7, HIGH_PRIORITY)

    val restored = mapper.readValue(mapper.writeValueAsString(task), Task.EnrichMetadata::class.java)

    assertThat(restored.bookId).isEqualTo("book")
    assertThat(restored.processor).isEqualTo(MetadataEnrichmentProcessor.TAG_TRANSLATION)
    assertThat(restored.revision).isEqualTo(7)
    assertThat(restored.priority).isEqualTo(HIGH_PRIORITY)
    assertThat(restored.uniqueId).isEqualTo(task.uniqueId)
    assertThat(restored.groupId).isEqualTo(task.groupId)
  }
}
