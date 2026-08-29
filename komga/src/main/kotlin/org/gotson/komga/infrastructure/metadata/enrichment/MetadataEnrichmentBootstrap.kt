package org.gotson.komga.infrastructure.metadata.enrichment

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.application.tasks.LOWEST_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.service.MetadataEnrichmentLifecycle
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
@Profile("!test")
class MetadataEnrichmentBootstrap(
  private val settings: MetadataEnrichmentSettingsProvider,
  private val dictionaryService: MetadataEnrichmentDictionaryService,
  private val lifecycle: MetadataEnrichmentLifecycle,
  private val bookRepository: BookRepository,
  private val taskEmitter: TaskEmitter,
) : ApplicationRunner {
  override fun run(args: ApplicationArguments) {
    val dictionaryHash = dictionaryService.fingerprint()
    if (settings.lastDictionaryHash.isBlank()) {
      settings.lastDictionaryHash = dictionaryHash
    } else if (settings.lastDictionaryHash != dictionaryHash) {
      logger.info { "Metadata enrichment dictionary changed while Komga was offline; invalidating translations" }
      lifecycle.invalidateDictionary()
      settings.lastDictionaryHash = dictionaryHash
    }

    lifecycle.recoverInterrupted()

    if (!settings.bootstrapCompleted) {
      val books = bookRepository.findAll().filter { it.deletedDate == null }
      logger.info { "Queueing metadata enrichment discovery for ${books.size} existing books" }
      taskEmitter.observeMetadataEnrichment(books, LOWEST_PRIORITY)
      settings.bootstrapCompleted = true
    }
  }
}
