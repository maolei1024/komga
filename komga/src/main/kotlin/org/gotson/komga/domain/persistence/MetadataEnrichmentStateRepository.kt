package org.gotson.komga.domain.persistence

import org.gotson.komga.domain.model.MetadataEnrichmentProcessor
import org.gotson.komga.domain.model.MetadataEnrichmentState
import org.gotson.komga.domain.model.MetadataEnrichmentStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface MetadataEnrichmentStateRepository {
  fun find(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
  ): MetadataEnrichmentState?

  fun findAllByBookId(bookId: String): List<MetadataEnrichmentState>

  fun findAllByProcessor(processor: MetadataEnrichmentProcessor): List<MetadataEnrichmentState>

  fun findAllByStatus(status: MetadataEnrichmentStatus): List<MetadataEnrichmentState>

  fun findAll(
    processor: MetadataEnrichmentProcessor? = null,
    status: MetadataEnrichmentStatus? = null,
    libraryId: String? = null,
    pageable: Pageable,
  ): Page<MetadataEnrichmentState>

  fun countByProcessorAndStatus(): Map<Pair<MetadataEnrichmentProcessor, MetadataEnrichmentStatus>, Long>

  fun save(state: MetadataEnrichmentState)

  fun save(states: Collection<MetadataEnrichmentState>)

  fun markRunning(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    revision: Long,
  ): Boolean

  fun markStale(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    revision: Long,
  ): Boolean

  fun markSuccess(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    revision: Long,
    resultJson: String,
  ): Boolean

  fun markFailure(
    bookId: String,
    processor: MetadataEnrichmentProcessor,
    revision: Long,
    error: String,
  ): Boolean

  fun resetRunning(): List<MetadataEnrichmentState>

  fun deleteByBookId(bookId: String): Int
}
