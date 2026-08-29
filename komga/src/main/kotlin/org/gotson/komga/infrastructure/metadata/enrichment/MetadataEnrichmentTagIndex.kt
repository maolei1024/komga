package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.persistence.MetadataEnrichmentStateRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service

@Service
class MetadataEnrichmentTagIndex(
  private val stateRepository: MetadataEnrichmentStateRepository,
  private val dictionaryService: MetadataEnrichmentDictionaryService,
  private val objectMapper: ObjectMapper,
) {
  @Volatile
  private var cache: Map<String, IndexedTag>? = null

  @Synchronized
  fun invalidate() {
    cache = null
  }

  @EventListener
  fun onBookDeleted(event: DomainEvent.BookDeleted) {
    invalidate()
  }

  fun bookIds(
    type: String,
    value: String,
  ): Set<String> = index()[key(type, value)]?.bookIds ?: emptySet()

  fun untranslated(
    query: String?,
    type: String?,
  ): List<MetadataEnrichmentMissingTag> {
    val normalizedQuery = query.orEmpty().trim().lowercase()
    val normalizedType = type.orEmpty().trim().lowercase()
    return index()
      .values
      .asSequence()
      .filter { dictionaryService.lookup(it.type, it.value) == null }
      .filter { normalizedType.isBlank() || it.type == normalizedType }
      .filter { normalizedQuery.isBlank() || it.value.lowercase().contains(normalizedQuery) }
      .map { MetadataEnrichmentMissingTag(it.type, it.value, it.bookIds.size) }
      .sortedWith(compareByDescending<MetadataEnrichmentMissingTag> { it.bookCount }.thenBy { it.type }.thenBy { it.value.lowercase() })
      .toList()
  }

  @Synchronized
  private fun index(): Map<String, IndexedTag> {
    cache?.let { return it }
    val mutable = linkedMapOf<String, MutableIndexedTag>()
    stateRepository.findAllByProcessor(MetadataEnrichmentProcessor.TAG_TRANSLATION).forEach { state ->
      runCatching { objectMapper.readValue(state.inputJson, MetadataEnrichmentSource::class.java) }.getOrNull()?.tags?.forEach { tag ->
        val indexed = mutable.getOrPut(tag.key) { MutableIndexedTag(tag.normalizedType, tag.value.trim()) }
        indexed.bookIds += state.bookId
      }
    }
    return mutable.mapValues { (_, value) -> IndexedTag(value.type, value.value, value.bookIds.toSet()) }.also { cache = it }
  }

  private fun key(
    type: String,
    value: String,
  ) = "${type.trim().lowercase()}:${value.trim().lowercase()}"

  private data class MutableIndexedTag(
    val type: String,
    val value: String,
    val bookIds: MutableSet<String> = linkedSetOf(),
  )

  private data class IndexedTag(
    val type: String,
    val value: String,
    val bookIds: Set<String>,
  )
}
