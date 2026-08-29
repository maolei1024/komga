package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.application.tasks.HIGHEST_PRIORITY
import org.gotson.komga.application.tasks.HIGH_PRIORITY
import org.gotson.komga.application.tasks.LOWEST_PRIORITY
import org.gotson.komga.application.tasks.LOW_PRIORITY
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.BookMetadataPatchCapability
import org.gotson.komga.domain.model.BookWithMedia
import org.gotson.komga.domain.model.MetadataEnrichmentBucket
import org.gotson.komga.domain.model.MetadataEnrichmentDictionaryUpdatePolicy
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentSourceTag
import org.gotson.komga.domain.model.MetadataEnrichmentState
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentAiClient
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentAiResult
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentBucketResult
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

  private fun observeLocked(bookId: String): Boolean {
    try {
      val book = bookRepository.findByIdOrNull(bookId) ?: return false
      val media = mediaRepository.findByIdOrNull(bookId) ?: return false
      val source = sourceExtractor.extract(BookWithMedia(book, media))
      if (source == null) {
        if (stateRepository.deleteByBookId(bookId) > 0) tagIndex.invalidate()
        return false
      }
      val changedStates = mutableListOf<MetadataEnrichmentState>()
      val inputJson = objectMapper.writeValueAsString(source)

      MetadataEnrichmentProcessor.entries.forEach { processor ->
        val current = stateRepository.find(bookId, processor)
        val inputHash = inputHash(processor, source)
        if (current == null) {
          changedStates += initialState(bookId, processor, source, inputHash, inputJson)
        } else if (current.inputHash != inputHash) {
          changedStates +=
            current.copy(
              status = if (autoOnSourceChange(processor)) MetadataEnrichmentStatus.WAITING else MetadataEnrichmentStatus.STALE,
              revision = current.revision + 1,
              inputHash = inputHash,
              inputJson = inputJson,
              lastError = null,
              startedDate = null,
              completedDate = null,
              lastModifiedDate = now(),
            )
        }
      }

      if (changedStates.isNotEmpty()) {
        stateRepository.save(changedStates)
        if (changedStates.any { it.processor == MetadataEnrichmentProcessor.TAG_TRANSLATION }) tagIndex.invalidate()
        changedStates.filter { it.status == MetadataEnrichmentStatus.WAITING }.forEach(::schedule)
      }
      return true
    } catch (e: Exception) {
      logger.warn(e) { "Could not observe metadata enrichment source for book $bookId" }
      return false
    }
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
        if (!stateRepository.markRunning(bookId, processor, revision)) return@synchronized null
        stateRepository
          .find(bookId, processor)
          ?.takeIf { it.revision == revision && it.status == MetadataEnrichmentStatus.RUNNING }
      } ?: return
    try {
      val source = objectMapper.readValue(state.inputJson, MetadataEnrichmentSource::class.java)
      val result =
        when (processor) {
          MetadataEnrichmentProcessor.AI_TITLE -> objectMapper.writeValueAsString(MetadataEnrichmentAiResult(aiClient.translate(source.summary)))
          MetadataEnrichmentProcessor.TAG_TRANSLATION -> objectMapper.writeValueAsString(translateTags(source.tags))
          MetadataEnrichmentProcessor.PAGE_SIZE -> objectMapper.writeValueAsString(MetadataEnrichmentBucketResult(resolveBucket(source.pageCount, settings.pageSizeBuckets)))
          MetadataEnrichmentProcessor.TAG_SIZE -> objectMapper.writeValueAsString(MetadataEnrichmentBucketResult(resolveBucket(source.tags.size, settings.tagSizeBuckets)))
        }
      if (synchronized(stateMutationLock) { stateRepository.markSuccess(bookId, processor, revision, result) }) {
        bookRepository.findByIdOrNull(bookId)?.let { book ->
          val capabilities =
            if (processor == MetadataEnrichmentProcessor.AI_TITLE) setOf(BookMetadataPatchCapability.TITLE) else setOf(BookMetadataPatchCapability.TAGS)
          taskEmitter.refreshBookMetadata(
            book,
            capabilities,
            minOf(priority + 1, HIGHEST_PRIORITY),
            requestId = "METADATA_ENRICHMENT_${processor.name}_$revision",
          )
        }
      }
    } catch (e: Exception) {
      if (processor == MetadataEnrichmentProcessor.AI_TITLE) {
        logger.warn { "Metadata enrichment failed for book $bookId, processor $processor, revision $revision: ${e.message.orEmpty()}" }
      } else {
        logger.warn(e) { "Metadata enrichment failed for book $bookId, processor $processor, revision $revision" }
      }
      synchronized(stateMutationLock) {
        stateRepository.markFailure(bookId, processor, revision, e.message ?: e.javaClass.simpleName)
      }
    }
  }

  fun requestRun(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    priority: Int = HIGH_PRIORITY,
  ): Boolean =
    synchronized(stateMutationLock) {
      if (processor == MetadataEnrichmentProcessor.AI_TITLE && (!settings.aiEnabled || !settings.aiConfigured)) return@synchronized false
      if (!observeLocked(bookId)) return@synchronized false
      val state = stateRepository.find(bookId, processor)
      state ?: return@synchronized false
      val queued =
        state.copy(
          status = MetadataEnrichmentStatus.WAITING,
          revision = state.revision + 1,
          lastError = null,
          startedDate = null,
          completedDate = null,
          lastModifiedDate = now(),
        )
      stateRepository.save(queued)
      taskEmitter.enrichMetadata(queued.bookId, queued.processor, queued.revision, priority)
      true
    }

  fun invalidate(
    processor: MetadataEnrichmentProcessor,
    bookIds: Collection<String>? = null,
    autoRun: Boolean,
  ): Int =
    synchronized(stateMutationLock) {
      val selected =
        stateRepository.findAllByProcessor(processor).filter { bookIds == null || it.bookId in bookIds }
      val updated =
        selected.map {
          it.copy(
            status = if (autoRun) MetadataEnrichmentStatus.WAITING else MetadataEnrichmentStatus.STALE,
            revision = it.revision + 1,
            lastError = null,
            startedDate = null,
            completedDate = null,
            lastModifiedDate = now(),
          )
        }
      stateRepository.save(updated)
      updated.filter { it.status == MetadataEnrichmentStatus.WAITING }.forEach(::schedule)
      if (processor == MetadataEnrichmentProcessor.TAG_TRANSLATION) tagIndex.invalidate()
      updated.size
    }

  fun invalidateDictionary(bookIds: Collection<String>? = null): Int =
    invalidate(
      MetadataEnrichmentProcessor.TAG_TRANSLATION,
      bookIds,
      settings.dictionaryUpdatePolicy == MetadataEnrichmentDictionaryUpdatePolicy.AUTO_LOW_PRIORITY,
    )

  fun recoverInterrupted() =
    synchronized(stateMutationLock) {
      val recovered = stateRepository.resetRunning()
      val waiting = stateRepository.findAllByStatus(MetadataEnrichmentStatus.WAITING)
      (recovered + waiting).distinctBy { Triple(it.bookId, it.processor, it.revision) }.forEach(::schedule)
    }

  private fun initialState(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    source: MetadataEnrichmentSource,
    inputHash: String,
    inputJson: String,
  ): MetadataEnrichmentState {
    val now = now()
    val base =
      MetadataEnrichmentState(
        bookId = bookId,
        processor = processor,
        status = MetadataEnrichmentStatus.STALE,
        revision = 1,
        inputHash = inputHash,
        inputJson = inputJson,
        createdDate = now,
        lastModifiedDate = now,
      )
    return when (processor) {
      MetadataEnrichmentProcessor.AI_TITLE ->
        when {
          source.legacyProcessed && source.originalTitle.isNotBlank() ->
            base.copy(
              status = MetadataEnrichmentStatus.SUCCESS,
              resultJson = objectMapper.writeValueAsString(MetadataEnrichmentAiResult(source.originalTitle)),
              resultRevision = 1,
              completedDate = now,
            )
          source.summary.isBlank() ->
            base.copy(
              status = MetadataEnrichmentStatus.SUCCESS,
              resultJson = objectMapper.writeValueAsString(MetadataEnrichmentAiResult()),
              resultRevision = 1,
              completedDate = now,
            )
          settings.aiEnabled && settings.aiConfigured && settings.aiAutoOnNew -> base.copy(status = MetadataEnrichmentStatus.WAITING)
          else -> base
        }
      MetadataEnrichmentProcessor.TAG_TRANSLATION ->
        if (source.legacyProcessed && source.existingTags.isNotEmpty()) {
          val adopted = adoptLegacyTags(source)
          base.copy(
            status = MetadataEnrichmentStatus.SUCCESS,
            resultJson = objectMapper.writeValueAsString(adopted),
            resultRevision = 1,
            completedDate = now,
          )
        } else {
          base.copy(status = MetadataEnrichmentStatus.WAITING)
        }
      MetadataEnrichmentProcessor.PAGE_SIZE -> adoptLegacyBucket(base, source, "pageSize_", now)
      MetadataEnrichmentProcessor.TAG_SIZE -> adoptLegacyBucket(base, source, "tagSize_", now)
    }
  }

  private fun adoptLegacyTags(source: MetadataEnrichmentSource): MetadataEnrichmentTagResult {
    val exactTags = source.existingTags.filterNot { it.startsWith("pageSize_") || it.startsWith("tagSize_") }
    val translated = translateTags(source.tags)
    val mapping =
      if (exactTags.size == source.tags.size) {
        source.tags.zip(exactTags).associate { (sourceTag, renderedTag) -> sourceTag.key to renderedTag }
      } else {
        translated.mapping
      }
    return translated.copy(mapping = mapping, exactTags = exactTags.toSet())
  }

  private fun adoptLegacyBucket(
    base: MetadataEnrichmentState,
    source: MetadataEnrichmentSource,
    prefix: String,
    completedDate: LocalDateTime,
  ): MetadataEnrichmentState {
    val existing = source.existingTags.firstOrNull { it.startsWith(prefix) }
    return base.copy(
      status = MetadataEnrichmentStatus.WAITING,
      resultJson = existing?.let { objectMapper.writeValueAsString(MetadataEnrichmentBucketResult(it)) },
      resultRevision = existing?.let { base.revision },
      completedDate = existing?.let { completedDate },
    )
  }

  private fun autoOnSourceChange(processor: MetadataEnrichmentProcessor): Boolean =
    when (processor) {
      MetadataEnrichmentProcessor.PAGE_SIZE -> true
      MetadataEnrichmentProcessor.TAG_SIZE -> true
      MetadataEnrichmentProcessor.AI_TITLE, MetadataEnrichmentProcessor.TAG_TRANSLATION -> false
    }

  private fun schedule(state: MetadataEnrichmentState) {
    taskEmitter.enrichMetadata(state.bookId, state.processor, state.revision, LOWEST_PRIORITY)
  }

  private fun inputHash(
    processor: MetadataEnrichmentProcessor,
    source: MetadataEnrichmentSource,
  ): String {
    val value =
      when (processor) {
        MetadataEnrichmentProcessor.AI_TITLE -> source.summary
        MetadataEnrichmentProcessor.TAG_TRANSLATION -> objectMapper.writeValueAsString(source.tags)
        MetadataEnrichmentProcessor.PAGE_SIZE -> listOf(source.sourceRevision, source.pageCount).joinToString("\u0000")
        MetadataEnrichmentProcessor.TAG_SIZE -> listOf(source.sourceRevision, objectMapper.writeValueAsString(source.tags)).joinToString("\u0000")
      }
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
  }

  private fun translateTags(tags: List<MetadataEnrichmentSourceTag>): MetadataEnrichmentTagResult {
    val mapping = linkedMapOf<String, String>()
    tags.forEach { tag ->
      val translated = dictionaryService.lookup(tag.normalizedType, tag.value) ?: tag.value.trim()
      mapping[tag.key] = renderTag(tag.normalizedType, translated)
    }
    return MetadataEnrichmentTagResult(mapping = mapping, exactTags = mapping.values.toSet())
  }

  private fun renderTag(
    type: String,
    value: String,
  ): String = if (type == "tag") value else "${type}_$value"

  private fun resolveBucket(
    value: Int,
    buckets: List<MetadataEnrichmentBucket>,
  ): String {
    require(value >= 0) { "Bucket input cannot be negative" }
    return buckets.firstOrNull { value >= it.min && (it.max == null || value <= it.max) }?.label
      ?: error("No bucket configured for value $value")
  }

  private fun now(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
}
