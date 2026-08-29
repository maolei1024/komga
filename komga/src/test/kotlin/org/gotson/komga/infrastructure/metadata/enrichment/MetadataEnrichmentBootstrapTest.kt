package org.gotson.komga.infrastructure.metadata.enrichment

import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.gotson.komga.application.tasks.LOWEST_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.service.MetadataEnrichmentLifecycle
import org.junit.jupiter.api.Test
import org.springframework.boot.ApplicationArguments

class MetadataEnrichmentBootstrapTest {
  @Test
  fun `offline dictionary changes are invalidated before recovery and initial discovery`() {
    val settings = mockk<MetadataEnrichmentSettingsProvider>(relaxed = true)
    val dictionary = mockk<MetadataEnrichmentDictionaryService>()
    val lifecycle = mockk<MetadataEnrichmentLifecycle>(relaxed = true)
    val bookRepository = mockk<BookRepository>()
    val taskEmitter = mockk<TaskEmitter>(relaxed = true)
    val book = makeBook("book.cbz")
    every { settings.lastDictionaryHash } returns "old"
    every { settings.bootstrapCompleted } returns false
    every { dictionary.fingerprint() } returns "new"
    every { bookRepository.findAll() } returns listOf(book)

    MetadataEnrichmentBootstrap(settings, dictionary, lifecycle, bookRepository, taskEmitter)
      .run(mockk<ApplicationArguments>(relaxed = true))

    verifyOrder {
      dictionary.fingerprint()
      lifecycle.invalidateDictionary()
      settings.lastDictionaryHash = "new"
      lifecycle.recoverInterrupted()
      bookRepository.findAll()
      taskEmitter.observeMetadataEnrichment(listOf(book), LOWEST_PRIORITY)
      settings.bootstrapCompleted = true
    }
  }
}
