package org.gotson.komga.interfaces.api.rest

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.MetadataEnrichmentBucket
import org.gotson.komga.domain.model.MetadataEnrichmentDictionaryUpdatePolicy
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.gotson.komga.domain.service.MetadataEnrichmentLifecycle
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentDictionaryService
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSettingsProvider
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentTagIndex
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentRunRequestDto
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentSettingsUpdateDto
import org.junit.jupiter.api.Test
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.server.ResponseStatusException

class MetadataEnrichmentControllerTest {
  private val settings = mockk<MetadataEnrichmentSettingsProvider>(relaxed = true)
  private val dictionary = mockk<MetadataEnrichmentDictionaryService>(relaxed = true)
  private val stateRepository = mockk<MetadataEnrichmentStateRepository>(relaxed = true)
  private val lifecycle = mockk<MetadataEnrichmentLifecycle>(relaxed = true)
  private val controller =
    MetadataEnrichmentController(
      settings,
      dictionary,
      mockk<MetadataEnrichmentTagIndex>(relaxed = true),
      stateRepository,
      mockk<BookRepository>(relaxed = true),
      mockk<BookMetadataRepository>(relaxed = true),
      lifecycle,
    )

  @Test
  fun `settings response exposes only whether the API key is configured`() {
    stubSettings(apiKey = "top-secret")
    every { dictionary.baseEntryCount() } returns 10
    every { dictionary.overrides() } returns emptyList()
    every { dictionary.fingerprint() } returns "hash"

    val json = jacksonObjectMapper().writeValueAsString(controller.getSettings())

    assertThat(json).contains("\"apiKeyConfigured\":true")
    assertThat(json).doesNotContain("top-secret").doesNotContain("aiApiKey")
  }

  @Test
  fun `invalid buckets are rejected before settings are persisted`() {
    stubSettings()
    val invalid =
      listOf(
        MetadataEnrichmentBucket(1, 10, "pageSize_a"),
        MetadataEnrichmentBucket(12, null, "pageSize_b"),
      )

    assertThatThrownBy { controller.updateSettings(MetadataEnrichmentSettingsUpdateDto(pageSizeBuckets = invalid)) }
      .isInstanceOf(ResponseStatusException::class.java)
    verify(exactly = 0) { settings.pageSizeBuckets = any() }
  }

  @Test
  fun `controller requires administrator role`() {
    assertThat(MetadataEnrichmentController::class.java.getAnnotation(PreAuthorize::class.java).value)
      .isEqualTo("hasRole('ADMIN')")
  }

  @Test
  fun `an explicitly empty book selection never expands to a bulk run`() {
    val result =
      controller.requestRuns(
        MetadataEnrichmentRunRequestDto(
          processor = MetadataEnrichmentProcessor.AI_TITLE,
          bookIds = emptySet(),
        ),
      )

    assertThat(result.accepted).isZero()
    verify(exactly = 0) { stateRepository.findAllByProcessor(any()) }
    verify(exactly = 0) { lifecycle.requestRun(any(), any(), any()) }
  }

  private fun stubSettings(apiKey: String = "") {
    every { settings.aiEnabled } returns false
    every { settings.aiAutoOnNew } returns true
    every { settings.aiBaseUrl } returns ""
    every { settings.aiModel } returns ""
    every { settings.aiApiKey } returns apiKey
    every { settings.aiTimeoutSeconds } returns 60
    every { settings.aiMaxRetries } returns 3
    every { settings.dictionaryUpdatePolicy } returns MetadataEnrichmentDictionaryUpdatePolicy.MARK_STALE
    every { settings.pageSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_PAGE_SIZE_BUCKETS
    every { settings.tagSizeBuckets } returns MetadataEnrichmentSettingsProvider.DEFAULT_TAG_SIZE_BUCKETS
  }
}
