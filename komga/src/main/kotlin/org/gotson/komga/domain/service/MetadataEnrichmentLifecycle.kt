package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.application.tasks.HIGHEST_PRIORITY
import org.gotson.komga.application.tasks.HIGH_PRIORITY
import org.gotson.komga.application.tasks.LOWEST_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.BookMetadataPatchCapability
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.MetadataEnrichmentDictionaryUpdatePolicy
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentState
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentAiClient
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentAiResult
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentBuckets
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentDictionaryService
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSettingsProvider
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSource
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSourceExtractor
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentTagIndex
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentTagResult
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset

private val logger = KotlinLogging.logger {}

@Service
class MetadataEnrichmentLifecycle(
  private val stateRepository: MetadataEnrichmentStateRepository,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val sourceExtractor: MetadataEnrichmentSourceExtractor,
  private val dictionaryService: MetadataEnrichmentDictionaryService,
  private val tagIndex: MetadataEnrichmentTagIndex,
  private val aiClient: MetadataEnrichmentAiClient,
  private val settings: MetadataEnrichmentSettingsProvider,
  private val taskEmitter: TaskEmitter,
  private val objectMapper: ObjectMapper,
) {
  private val stateMutationLock = Any()

  fun observe(bookId: String): Boolean = synchronized(stateMutationLock) { observeLocked(bookId) }

  private fun observeLocked(bookId: String): Boolean =
    try {
      val book = bookRepository.findByIdOrNull(bookId)
      val media = mediaRepository.findByIdOrNull(bookId)
      if (book == null || media == null || media.status != Media.Status.READY) {
        false
      } else {
        val previous =
          stateRepository
            .find(bookId, MetadataEnrichmentProcessor.METADATA)
            ?.let { runCatching { objectMapper.readValue(it.inputJson, MetadataEnrichmentSource::class.java) }.getOrNull() }
        val bookWithMedia = BookWithMedia(book, media)
        val extracted =
          if (previous?.sourceRevision == MetadataEnrichmentSourceExtractor.revision(bookWithMedia))
            previous.copy(pageCount = media.pageCount)
          else
            sourceExtractor.extract(bookWithMedia)
        if (extracted == null) {
          false
        } else {
          val unknown = previous?.unclassifiedTags.orEmpty()
          val unknownKeys = unknown.map { it.trim().lowercase() }.toSet()
          val source =
            if (extracted.contentTagsFromJson || unknown.isEmpty())
              extracted.copy(unclassifiedTags = unknown)
            else
              extracted.copy(
                tags = extracted.tags.filterNot { it.normalizedType == "tag" && it.normalizedValue in unknownKeys },
                contentTagsKnown = false,
                unclassifiedTags = unknown,
                fallbackTags =
                  if (previous?.fallbackTags != null)
                    extracted.fallbackTags ?: (
                      extracted.tags
                        .filter { it.normalizedType == "tag" }
                        .map { it.normalizedValue }
                        .toSet() + unknown
                    )
                  else
                    null,
              )
          val inputJson = objectMapper.writeValueAsString(source)
          val changed =
            MetadataEnrichmentProcessor.entries.mapNotNull { processor ->
              val current = stateRepository.find(bookId, processor)
              val input =
                if (processor == MetadataEnrichmentProcessor.AI_TITLE)
                  source.summary.ifBlank { source.originalTitle }
                else
                  inputJson + objectMapper.writeValueAsString(settings.pageSizeBuckets) +
                    objectMapper.writeValueAsString(settings.tagSizeBuckets) + dictionaryService.fingerprint()
              val hash = MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
              when {
                current == null ->
                  MetadataEnrichmentState(
                    bookId = bookId,
                    processor = processor,
                    status =
                      if (processor == MetadataEnrichmentProcessor.METADATA ||
                        input.isNotBlank() && settings.aiEnabled && settings.aiConfigured && settings.aiAutoOnNew
                      )
                        MetadataEnrichmentStatus.WAITING
                      else
                        MetadataEnrichmentStatus.STALE,
                    revision = 1,
                    inputHash = hash,
                    inputJson = inputJson,
                  )
                current.inputHash != hash ->
                  current.copy(
                    status = if (processor == MetadataEnrichmentProcessor.METADATA) MetadataEnrichmentStatus.WAITING else MetadataEnrichmentStatus.STALE,
                    revision = current.revision + 1,
                    inputHash = hash,
                    inputJson = inputJson,
                    lastError = null,
                    startedDate = null,
                    completedDate = null,
                    lastModifiedDate = now(),
                  )
                else -> null
              }
            }
          if (changed.isNotEmpty()) {
            stateRepository.save(changed)
            tagIndex.invalidate()
            changed.filter { it.status == MetadataEnrichmentStatus.WAITING }.forEach(::schedule)
          }
          true
        }
      }
    } catch (exception: Exception) {
      logger.warn(exception) { "Could not observe metadata enrichment source for book $bookId" }
      false
    }

  fun process(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    revision: Long,
    priority: Int,
  ) {
    if (processor == MetadataEnrichmentProcessor.AI_TITLE && (!settings.aiEnabled || !settings.aiConfigured)) {
      synchronized(stateMutationLock) { stateRepository.markStale(bookId, processor, revision) }
      return
    }
    val state =
      synchronized(stateMutationLock) {
        if (!stateRepository.markRunning(bookId, processor, revision))
          null
        else
          stateRepository.find(bookId, processor)?.takeIf { it.revision == revision && it.status == MetadataEnrichmentStatus.RUNNING }
      } ?: return
    try {
      val source = objectMapper.readValue(state.inputJson, MetadataEnrichmentSource::class.java)
      val result =
        when (processor) {
          MetadataEnrichmentProcessor.AI_TITLE ->
            objectMapper.writeValueAsString(
              MetadataEnrichmentAiResult(aiClient.translate(source.summary.ifBlank { source.originalTitle })),
            )
          MetadataEnrichmentProcessor.METADATA ->
            objectMapper.writeValueAsString(
              MetadataEnrichmentTagResult(
                mapping =
                  source.tags.associate { tag ->
                    tag.key to (dictionaryService.lookup(tag.normalizedType, tag.value) ?: tag.value.trim())
                  },
                preferenceBuckets = MetadataEnrichmentBuckets.labels(source, settings),
              ),
            )
        }
      if (synchronized(stateMutationLock) { stateRepository.markSuccess(bookId, processor, revision, result) }) {
        bookRepository.findByIdOrNull(bookId)?.let { book ->
          taskEmitter.refreshBookMetadata(
            book,
            if (processor == MetadataEnrichmentProcessor.AI_TITLE)
              setOf(BookMetadataPatchCapability.TITLE)
            else
              setOf(BookMetadataPatchCapability.TAGS, BookMetadataPatchCapability.AUTHORS),
            minOf(priority + 1, HIGHEST_PRIORITY),
            requestId = "METADATA_ENRICHMENT_${processor.name}_$revision",
          )
        }
      }
    } catch (exception: Exception) {
      logger.warn { "Metadata enrichment failed for book $bookId, processor $processor, revision $revision: ${exception.javaClass.simpleName}" }
      synchronized(stateMutationLock) {
        stateRepository.markFailure(bookId, processor, revision, exception.javaClass.simpleName)
      }
    }
  }

  fun requestRun(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    priority: Int = HIGH_PRIORITY,
  ): Boolean =
    synchronized(stateMutationLock) {
      if (processor == MetadataEnrichmentProcessor.AI_TITLE && (!settings.aiEnabled || !settings.aiConfigured)) {
        false
      } else if (!observeLocked(bookId)) {
        false
      } else {
        val state = stateRepository.find(bookId, processor)
        if (state == null) {
          false
        } else {
          val queued = state.copy(status = MetadataEnrichmentStatus.WAITING, revision = state.revision + 1, lastError = null, lastModifiedDate = now())
          stateRepository.save(queued)
          taskEmitter.enrichMetadata(bookId, processor, queued.revision, priority)
          true
        }
      }
    }

  fun invalidate(
    processor: MetadataEnrichmentProcessor,
    bookIds: Collection<String>? = null,
    autoRun: Boolean,
  ): Int =
    synchronized(stateMutationLock) {
      val states =
        stateRepository.findAllByProcessor(processor).filter { bookIds == null || it.bookId in bookIds }.map {
          it.copy(
            status = if (autoRun) MetadataEnrichmentStatus.WAITING else MetadataEnrichmentStatus.STALE,
            revision = it.revision + 1,
            lastError = null,
            startedDate = null,
            completedDate = null,
            lastModifiedDate = now(),
          )
        }
      stateRepository.save(states)
      states.filter { it.status == MetadataEnrichmentStatus.WAITING }.forEach(::schedule)
      tagIndex.invalidate()
      states.size
    }

  fun invalidateDictionary(bookIds: Collection<String>? = null): Int = invalidate(MetadataEnrichmentProcessor.METADATA, bookIds, settings.dictionaryUpdatePolicy == MetadataEnrichmentDictionaryUpdatePolicy.AUTO_LOW_PRIORITY)

  fun recoverInterrupted() =
    synchronized(stateMutationLock) {
      (stateRepository.resetRunning() + stateRepository.findAllByStatus(MetadataEnrichmentStatus.WAITING))
        .distinctBy { it.bookId to it.processor }
        .forEach(::schedule)
    }

  private fun schedule(state: MetadataEnrichmentState) {
    taskEmitter.enrichMetadata(state.bookId, state.processor, state.revision, LOWEST_PRIORITY)
  }

  private fun now(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
}
