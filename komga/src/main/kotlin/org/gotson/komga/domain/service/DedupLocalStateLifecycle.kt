package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.BookMetadata
import org.gotson.komga.domain.model.DedupLocalStateSnapshot
import org.gotson.komga.domain.model.SeriesMetadata
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.ThumbnailSeries
import org.gotson.komga.domain.persistence.BookMetadataRepository
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.persistence.SeriesCollectionRepository
import org.gotson.komga.domain.persistence.SeriesMetadataRepository
import org.gotson.komga.domain.persistence.ThumbnailBookRepository
import org.gotson.komga.domain.persistence.ThumbnailSeriesRepository
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.TreeMap

@Service
class DedupLocalStateLifecycle(
  private val bookRepository: BookRepository,
  private val readProgressRepository: ReadProgressRepository,
  private val readListRepository: ReadListRepository,
  private val seriesCollectionRepository: SeriesCollectionRepository,
  private val thumbnailBookRepository: ThumbnailBookRepository,
  private val thumbnailSeriesRepository: ThumbnailSeriesRepository,
  private val bookMetadataRepository: BookMetadataRepository,
  private val seriesMetadataRepository: SeriesMetadataRepository,
  private val objectMapper: ObjectMapper,
) {
  fun snapshot(bookId: String): DedupLocalStateSnapshot {
    val book = requireNotNull(bookRepository.findByIdOrNull(bookId)) { "Book $bookId no longer exists" }
    val readProgress =
      readProgressRepository
        .findAllByBookId(bookId)
        .sortedBy { it.userId }
        .map { mapOf("userId" to it.userId, "page" to it.page, "completed" to it.completed, "lastModified" to it.lastModifiedDate.toString()) }
    val readLists =
      readListRepository
        .findAllContainingBookId(bookId, null)
        .sortedBy { it.id }
        .map { mapOf("id" to it.id, "name" to it.name, "lastModified" to it.lastModifiedDate.toString()) }
    val bookThumbnails =
      thumbnailBookRepository
        .findAllByBookIdAndType(bookId, setOf(ThumbnailBook.Type.USER_UPLOADED))
        .sortedBy { it.id }
        .map { mapOf("id" to it.id, "selected" to it.selected, "lastModified" to it.lastModifiedDate.toString()) }
    val bookMetadata = bookMetadataRepository.findByIdOrNull(bookId)

    val activeBooks = bookRepository.findAllBySeriesId(book.seriesId).filter { it.deletedDate == null }
    val losingSeries = activeBooks.map { it.id } == listOf(bookId)
    val collections =
      if (losingSeries) {
        seriesCollectionRepository
          .findAllContainingSeriesId(book.seriesId, null)
          .sortedBy { it.id }
          .map { mapOf("id" to it.id, "name" to it.name, "lastModified" to it.lastModifiedDate.toString()) }
      } else {
        emptyList()
      }
    val seriesThumbnails =
      if (losingSeries) {
        thumbnailSeriesRepository
          .findAllBySeriesIdIdAndType(book.seriesId, ThumbnailSeries.Type.USER_UPLOADED)
          .sortedBy { it.id }
          .map { mapOf("id" to it.id, "selected" to it.selected, "lastModified" to it.lastModifiedDate.toString()) }
      } else {
        emptyList()
      }
    val seriesMetadata = if (losingSeries) seriesMetadataRepository.findByIdOrNull(book.seriesId) else null

    val details =
      TreeMap<String, Any>().apply {
        put("readProgress", readProgress)
        put("readLists", readLists)
        put("collections", collections)
        put("bookUserThumbnails", bookThumbnails)
        put("seriesUserThumbnails", seriesThumbnails)
        put("bookMetadataLocked", bookMetadata?.hasLocks() == true)
        put("seriesMetadataLocked", seriesMetadata?.hasLocks() == true)
        put("seriesWillHaveNoActiveBooks", losingSeries)
      }
    val reasonCodes =
      buildSet {
        if (readProgress.isNotEmpty()) add("READ_PROGRESS_PRESENT")
        if (readLists.isNotEmpty()) add("READLIST_PRESENT")
        if (collections.isNotEmpty()) add("COLLECTION_PRESENT")
        if (bookThumbnails.isNotEmpty() || seriesThumbnails.isNotEmpty() || bookMetadata?.hasLocks() == true || seriesMetadata?.hasLocks() == true) {
          add("USER_THUMBNAIL_OR_LOCKED_METADATA")
        }
      }
    val canonical = objectMapper.writeValueAsString(details)
    return DedupLocalStateSnapshot(bookId, stableHash(canonical), reasonCodes, details)
  }

  private fun stableHash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

  private fun BookMetadata.hasLocks(): Boolean = titleLock || summaryLock || numberLock || numberSortLock || releaseDateLock || authorsLock || tagsLock || isbnLock || linksLock

  private fun SeriesMetadata.hasLocks(): Boolean = statusLock || titleLock || titleSortLock || summaryLock || readingDirectionLock || publisherLock || ageRatingLock || languageLock || genresLock || tagsLock || totalBookCountLock || sharingLabelsLock || linksLock || alternateTitlesLock
}
