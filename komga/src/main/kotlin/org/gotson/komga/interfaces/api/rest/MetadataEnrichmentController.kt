package org.gotson.komga.interfaces.api.rest

import com.fasterxml.jackson.core.JsonProcessingException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.gotson.komga.application.tasks.HIGH_PRIORITY
import org.gotson.komga.application.tasks.LOW_PRIORITY
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.gotson.komga.domain.service.MetadataEnrichmentLifecycle
import org.gotson.komga.infrastructure.metadata.enrichment.EhtagsDictionaryEntry
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentDictionaryService
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSettingsProvider
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentTagIndex
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentDictionaryResultDto
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentMissingTagDto
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentOverrideDto
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentRunRequestDto
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentRunResultDto
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentSettingsDto
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentSettingsUpdateDto
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentStateDto
import org.gotson.komga.interfaces.api.rest.dto.MetadataEnrichmentStatusCountDto
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.net.URI

@RestController
@RequestMapping(value = ["api/v1/metadata-enrichment"], produces = [MediaType.APPLICATION_JSON_VALUE])
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "metadata-enrichment")
class MetadataEnrichmentController(
  private val settings: MetadataEnrichmentSettingsProvider,
  private val dictionaryService: MetadataEnrichmentDictionaryService,
  private val tagIndex: MetadataEnrichmentTagIndex,
  private val stateRepository: MetadataEnrichmentStateRepository,
  private val bookRepository: BookRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val lifecycle: MetadataEnrichmentLifecycle,
) {
  @GetMapping
  @Operation(summary = "Retrieve metadata enrichment settings")
  fun getSettings(): MetadataEnrichmentSettingsDto = settingsDto()

  @PatchMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @Operation(summary = "Update metadata enrichment settings")
  fun updateSettings(
    @RequestBody update: MetadataEnrichmentSettingsUpdateDto,
  ) {
    val nextBaseUrl = update.aiBaseUrl?.trim() ?: settings.aiBaseUrl
    val nextModel = update.aiModel?.trim() ?: settings.aiModel
    val nextPrompt = update.aiPrompt?.trim() ?: settings.aiPrompt
    val nextEnabled = update.aiEnabled ?: settings.aiEnabled
    val clearKey = update.clearAiApiKey == true
    if (clearKey && update.aiApiKey != null) badRequest("Cannot set and clear the AI API key in the same request")
    val nextKeyConfigured =
      when {
        clearKey -> false
        update.aiApiKey != null -> update.aiApiKey.isNotBlank()
        else -> settings.aiApiKey.isNotBlank()
      }

    validateBaseUrl(nextBaseUrl)
    if (nextPrompt.isBlank()) badRequest("AI prompt cannot be blank")
    if (nextPrompt.length > 20_000) badRequest("AI prompt cannot exceed 20000 characters")
    update.aiTimeoutSeconds?.let { if (it !in 1..600) badRequest("AI timeout must be between 1 and 600 seconds") }
    update.aiMaxRetries?.let { if (it !in 0..10) badRequest("AI max retries must be between 0 and 10") }
    update.pageSizeBuckets?.let { validateBuckets(it, 1, "pageSize_") }
    update.tagSizeBuckets?.let { validateBuckets(it, 0, "tagSize_") }
    if (nextEnabled && (nextBaseUrl.isBlank() || nextModel.isBlank() || nextPrompt.isBlank() || !nextKeyConfigured)) {
      badRequest("AI requires a base URL, model, prompt, and API key before it can be enabled")
    }

    val aiDefinitionChanged = nextBaseUrl != settings.aiBaseUrl || nextModel != settings.aiModel || nextPrompt != settings.aiPrompt
    val aiDisabled = settings.aiEnabled && !nextEnabled
    val pageBucketsChanged = update.pageSizeBuckets != null && update.pageSizeBuckets != settings.pageSizeBuckets
    val tagBucketsChanged = update.tagSizeBuckets != null && update.tagSizeBuckets != settings.tagSizeBuckets

    update.aiBaseUrl?.let { settings.aiBaseUrl = it }
    update.aiModel?.let { settings.aiModel = it }
    update.aiPrompt?.let { settings.aiPrompt = it }
    if (clearKey) settings.clearAiApiKey() else update.aiApiKey?.let { settings.aiApiKey = it }
    update.aiAutoOnNew?.let { settings.aiAutoOnNew = it }
    update.aiTimeoutSeconds?.let { settings.aiTimeoutSeconds = it }
    update.aiMaxRetries?.let { settings.aiMaxRetries = it }
    update.dictionaryUpdatePolicy?.let { settings.dictionaryUpdatePolicy = it }
    update.pageSizeBuckets?.let { settings.pageSizeBuckets = it }
    update.tagSizeBuckets?.let { settings.tagSizeBuckets = it }
    update.aiEnabled?.let { settings.aiEnabled = it }

    if (aiDefinitionChanged || aiDisabled) lifecycle.invalidate(MetadataEnrichmentProcessor.AI_TITLE, autoRun = false)
    if (pageBucketsChanged) lifecycle.invalidate(MetadataEnrichmentProcessor.PAGE_SIZE, autoRun = true)
    if (tagBucketsChanged) lifecycle.invalidate(MetadataEnrichmentProcessor.TAG_SIZE, autoRun = true)
  }

  @GetMapping("stats")
  @Operation(summary = "Retrieve metadata enrichment status counts")
  fun getStats(): List<MetadataEnrichmentStatusCountDto> {
    val counts = stateRepository.countByProcessorAndStatus()
    return MetadataEnrichmentProcessor.entries.flatMap { processor ->
      MetadataEnrichmentStatus.entries.map { status ->
        MetadataEnrichmentStatusCountDto(processor, status, counts[processor to status] ?: 0)
      }
    }
  }

  @GetMapping("states")
  @Operation(summary = "Retrieve metadata enrichment states")
  fun getStates(
    @RequestParam(required = false) processor: MetadataEnrichmentProcessor?,
    @RequestParam(required = false) status: MetadataEnrichmentStatus?,
    @RequestParam(required = false) libraryId: String?,
    @PageableDefault(size = 20) pageable: Pageable,
  ): Page<MetadataEnrichmentStateDto> {
    val states = stateRepository.findAll(processor, status, libraryId, pageable)
    val bookIds = states.content.map { it.bookId }
    val books = bookRepository.findAllByIds(bookIds).associateBy { it.id }
    val metadata = bookMetadataRepository.findAllByIds(bookIds).associateBy { it.bookId }
    return states.map { state ->
      val book = books[state.bookId]
      MetadataEnrichmentStateDto(
        bookId = state.bookId,
        bookName = book?.name.orEmpty(),
        bookTitle = metadata[state.bookId]?.title.orEmpty(),
        seriesId = book?.seriesId.orEmpty(),
        libraryId = book?.libraryId.orEmpty(),
        processor = state.processor,
        status = state.status,
        revision = state.revision,
        resultRevision = state.resultRevision,
        hasResult = state.resultJson != null,
        lastError = state.lastError,
        startedDate = state.startedDate,
        completedDate = state.completedDate,
        lastModifiedDate = state.lastModifiedDate,
      )
    }
  }

  @PostMapping("runs")
  @Operation(summary = "Queue metadata enrichment runs")
  fun requestRuns(
    @RequestBody request: MetadataEnrichmentRunRequestDto,
  ): MetadataEnrichmentRunResultDto {
    val explicitIds =
      request.bookIds
        ?.filter { it.isNotBlank() }
        ?.toSet()
        .orEmpty()
    val candidateIds =
      if (request.bookIds != null) {
        explicitIds.filter { bookId -> matchesFilters(bookId, request) }
      } else {
        stateRepository
          .findAllByProcessor(request.processor)
          .asSequence()
          .filter { request.status == null || it.status == request.status }
          .map { it.bookId }
          .filter { request.libraryId.isNullOrBlank() || bookRepository.getLibraryIdOrNull(it) == request.libraryId }
          .distinct()
          .toList()
      }
    val priority = if (request.bookIds != null && explicitIds.size == 1 && candidateIds.size == 1) HIGH_PRIORITY else LOW_PRIORITY
    val accepted = candidateIds.count { lifecycle.requestRun(it, request.processor, priority) }
    return MetadataEnrichmentRunResultDto(accepted)
  }

  @PostMapping("dictionary/base", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
  @Operation(summary = "Atomically replace the base tag dictionary")
  fun replaceBaseDictionary(
    @RequestPart("file") file: MultipartFile,
  ): MetadataEnrichmentDictionaryResultDto {
    if (file.isEmpty) badRequest("Dictionary file cannot be empty")
    try {
      dictionaryService.replaceBase(file.bytes)
    } catch (e: JsonProcessingException) {
      badRequest("Dictionary is not valid JSON: ${e.originalMessage}")
    } catch (e: IllegalArgumentException) {
      badRequest(e.message ?: "Dictionary is invalid")
    }
    settings.lastDictionaryHash = dictionaryService.fingerprint()
    tagIndex.invalidate()
    val invalidated = lifecycle.invalidateDictionary()
    return dictionaryResult(invalidated)
  }

  @GetMapping("dictionary/overrides")
  @Operation(summary = "Retrieve tag dictionary overrides")
  fun getOverrides(): List<MetadataEnrichmentOverrideDto> = dictionaryService.overrides().map { MetadataEnrichmentOverrideDto(it.k, it.v, it.t, it.n) }

  @PutMapping("dictionary/overrides")
  @Operation(summary = "Create or replace a tag dictionary override")
  fun putOverride(
    @RequestBody override: MetadataEnrichmentOverrideDto,
  ): MetadataEnrichmentDictionaryResultDto {
    val affectedBooks = tagIndex.bookIds(override.t, override.k)
    try {
      dictionaryService.putOverride(EhtagsDictionaryEntry(override.k, override.v, override.t, override.n))
    } catch (e: IllegalArgumentException) {
      badRequest(e.message ?: "Dictionary override is invalid")
    }
    settings.lastDictionaryHash = dictionaryService.fingerprint()
    tagIndex.invalidate()
    val invalidated = lifecycle.invalidateDictionary(affectedBooks)
    return dictionaryResult(invalidated)
  }

  @DeleteMapping("dictionary/overrides")
  @Operation(summary = "Delete a tag dictionary override")
  fun deleteOverride(
    @RequestParam type: String,
    @RequestParam key: String,
  ): MetadataEnrichmentDictionaryResultDto {
    val affectedBooks = tagIndex.bookIds(type, key)
    try {
      if (!dictionaryService.deleteOverride(type, key)) throw ResponseStatusException(HttpStatus.NOT_FOUND, "Dictionary override not found")
    } catch (e: IllegalArgumentException) {
      badRequest(e.message ?: "Dictionary override is invalid")
    }
    settings.lastDictionaryHash = dictionaryService.fingerprint()
    tagIndex.invalidate()
    val invalidated = lifecycle.invalidateDictionary(affectedBooks)
    return dictionaryResult(invalidated)
  }

  @GetMapping("untranslated-tags")
  @Operation(summary = "Retrieve tags without a dictionary translation")
  fun getUntranslatedTags(
    @RequestParam(required = false) search: String?,
    @RequestParam(required = false) type: String?,
    @PageableDefault(size = 20) pageable: Pageable,
  ): Page<MetadataEnrichmentMissingTagDto> {
    val all = tagIndex.untranslated(search, type)
    val from = if (pageable.isPaged) pageable.offset.coerceAtMost(all.size.toLong()).toInt() else 0
    val to = if (pageable.isPaged) minOf(from + pageable.pageSize, all.size) else all.size
    val content = all.subList(from, to).map { MetadataEnrichmentMissingTagDto(it.type, it.value, it.bookCount) }
    return PageImpl(content, pageable, all.size.toLong())
  }

  private fun settingsDto() =
    MetadataEnrichmentSettingsDto(
      aiEnabled = settings.aiEnabled,
      aiAutoOnNew = settings.aiAutoOnNew,
      aiBaseUrl = settings.aiBaseUrl,
      aiModel = settings.aiModel,
      aiPrompt = settings.aiPrompt,
      apiKeyConfigured = settings.aiApiKey.isNotBlank(),
      aiTimeoutSeconds = settings.aiTimeoutSeconds,
      aiMaxRetries = settings.aiMaxRetries,
      dictionaryUpdatePolicy = settings.dictionaryUpdatePolicy,
      pageSizeBuckets = settings.pageSizeBuckets,
      tagSizeBuckets = settings.tagSizeBuckets,
      baseDictionaryEntryCount = dictionaryService.baseEntryCount(),
      overrideEntryCount = dictionaryService.overrides().size,
      dictionaryFingerprint = dictionaryService.fingerprint(),
    )

  private fun dictionaryResult(invalidatedBooks: Int) =
    MetadataEnrichmentDictionaryResultDto(
      baseDictionaryEntryCount = dictionaryService.baseEntryCount(),
      overrideEntryCount = dictionaryService.overrides().size,
      dictionaryFingerprint = dictionaryService.fingerprint(),
      invalidatedBooks = invalidatedBooks,
    )

  private fun matchesFilters(
    bookId: String,
    request: MetadataEnrichmentRunRequestDto,
  ): Boolean {
    val book = bookRepository.findByIdOrNull(bookId) ?: return false
    if (book.deletedDate != null) return false
    if (!request.libraryId.isNullOrBlank() && book.libraryId != request.libraryId) return false
    val state = stateRepository.find(bookId, request.processor)
    return request.status == null || state?.status == request.status
  }

  private fun validateBaseUrl(value: String) {
    if (value.isBlank()) return
    val uri =
      try {
        URI(value)
      } catch (_: Exception) {
        badRequest("AI base URL must be a valid HTTP(S) URL")
      }
    if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
      badRequest("AI base URL must be a valid HTTP(S) URL")
    }
  }

  private fun validateBuckets(
    buckets: List<org.gotson.komga.domain.model.MetadataEnrichmentBucket>,
    expectedStart: Int,
    prefix: String,
  ) {
    try {
      MetadataEnrichmentSettingsProvider.validateBuckets(buckets, expectedStart, prefix)
    } catch (e: IllegalArgumentException) {
      badRequest(e.message ?: "Bucket configuration is invalid")
    }
  }

  private fun badRequest(message: String): Nothing = throw ResponseStatusException(HttpStatus.BAD_REQUEST, message)
}
