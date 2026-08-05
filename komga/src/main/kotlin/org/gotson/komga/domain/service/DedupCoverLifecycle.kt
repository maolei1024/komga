package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DEDUP_ARCHIVE_HASH_SCHEMA_VERSION
import org.gotson.komga.domain.model.DedupArchiveHashState
import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupFeatureState
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.dedupContentGeneration
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.ExactDuplicateBookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ThumbnailBookRepository
import org.gotson.komga.infrastructure.dedup.CoverPerceptualHasher
import org.gotson.komga.infrastructure.dedup.CoverSimilarityIndex
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime

@Service
class DedupCoverLifecycle(
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val thumbnailBookRepository: ThumbnailBookRepository,
  private val exactDuplicateBookRepository: ExactDuplicateBookRepository,
  private val dedupRepository: DedupRepository,
  private val coverHasher: CoverPerceptualHasher,
  private val coverIndex: CoverSimilarityIndex,
  private val objectMapper: ObjectMapper,
) {
  companion object {
    const val FEATURE_SCHEMA_VERSION = 1
  }

  fun findDirtyBookIds(libraryId: String): List<String> {
    val eligible = eligibleBooks(libraryId)
    dedupRepository.deleteFeaturesNotIn(libraryId, eligible.map { it.id }.toSet())
    return eligible
      .filter { book ->
        val source = sourceSnapshot(book) ?: return@filter false
        val current = dedupRepository.findFeature(book.id)
        current == null ||
          current.featureSchemaVersion != FEATURE_SCHEMA_VERSION ||
          current.sourceContentGeneration != source.contentGeneration ||
          current.sourceCoverGeneration != source.coverGeneration ||
          current.sourceMetadataGeneration != source.metadataGeneration ||
          current.seriesScopeRevision != source.scopeRevision ||
          current.coverState == DedupFeatureState.STALE
      }.map { it.id }
  }

  fun computeCover(bookId: String) {
    val book = bookRepository.findByIdOrNull(bookId) ?: return
    val before = sourceSnapshot(book) ?: return
    val now = LocalDateTime.now()
    val bytes = before.thumbnail?.thumbnail
    val hashResult = bytes?.let(coverHasher::hash)
    val after = bookRepository.findByIdOrNull(bookId)?.let(::sourceSnapshot)
    check(after != null && before.matches(after)) { "Book or selected thumbnail changed during cover analysis" }

    dedupRepository.saveFeature(
      DedupFeature(
        bookId = book.id,
        seriesId = book.seriesId,
        libraryId = book.libraryId,
        sourceContentGeneration = before.contentGeneration,
        sourceCoverGeneration = before.coverGeneration,
        sourceMetadataGeneration = before.metadataGeneration,
        seriesScopeRevision = before.scopeRevision,
        featureSchemaVersion = FEATURE_SCHEMA_VERSION,
        coverState = if (hashResult != null) DedupFeatureState.READY else DedupFeatureState.WAITING,
        coverSource =
          when {
            before.thumbnail == null -> "MISSING_THUMBNAIL"
            before.thumbnail.thumbnail != null -> "BOOK_THUMBNAIL_BLOB"
            else -> "REMOTE_COVER_DEFERRED"
          },
        coverHash = hashResult?.hash,
        coverQuality = hashResult?.quality,
        pageCount = before.pageCount,
        analyzedDate = now,
        lastModifiedDate = now,
        archiveHash = before.archiveHash,
        archiveHashPath = before.archiveHashPath,
        archiveHashSize = before.archiveHashSize,
        archiveHashSchemaVersion = before.archiveHashSchemaVersion,
        archiveHashDate = before.archiveHashDate,
      ),
    )
  }

  fun rebuildCandidates(libraryId: String): Int {
    val settings = dedupRepository.findLibrarySettings(libraryId) ?: return 0
    val features = dedupRepository.findReadyCoverFeatures(libraryId)
    coverIndex.replaceLibrary(libraryId, features, settings.coverCandidateDistance)
    val byBookId = features.associateBy { it.bookId }
    val exactPairs = exactPairs(libraryId)
    val now = LocalDateTime.now()
    val relations =
      coverIndex
        .findAllNeighbors(libraryId, settings.coverTopK)
        .filterNot { (it.bookLowId to it.bookHighId) in exactPairs }
        .mapNotNull { neighbor ->
          val low = byBookId[neighbor.bookLowId] ?: return@mapNotNull null
          val high = byBookId[neighbor.bookHighId] ?: return@mapNotNull null
          val pairIdentity = "${neighbor.bookLowId}|${neighbor.bookHighId}"
          val candidateRelation =
            DedupRelation(
              id = "cover-relation-${stableHash(pairIdentity)}",
              libraryId = libraryId,
              bookLowId = neighbor.bookLowId,
              bookHighId = neighbor.bookHighId,
              lowContentGeneration = low.sourceContentGeneration,
              highContentGeneration = high.sourceContentGeneration,
              lowCoverGeneration = low.sourceCoverGeneration,
              highCoverGeneration = high.sourceCoverGeneration,
              lowMetadataGeneration = low.sourceMetadataGeneration,
              highMetadataGeneration = high.sourceMetadataGeneration,
              type = DedupRelationType.VISUALLY_SIMILAR,
              coverDistance = neighbor.distance,
              evidenceJson =
                objectMapper.writeValueAsString(
                  mapOf(
                    "coverDistance" to neighbor.distance,
                    "hashBits" to 256,
                    "candidateThreshold" to settings.coverCandidateDistance,
                  ),
                ),
              createdDate = now,
              lastModifiedDate = now,
            )
          val currentRelation = dedupRepository.findRelation(neighbor.bookLowId, neighbor.bookHighId)
          val relation =
            currentRelation
              ?.takeIf {
                it.type != DedupRelationType.VISUALLY_SIMILAR &&
                  it.lowContentGeneration == low.sourceContentGeneration &&
                  it.highContentGeneration == high.sourceContentGeneration
              }?.copy(
                lowCoverGeneration = low.sourceCoverGeneration,
                highCoverGeneration = high.sourceCoverGeneration,
                coverDistance = neighbor.distance,
                lastModifiedDate = now,
              ) ?: candidateRelation
          relation
        }

    dedupRepository.replaceCoverRelations(libraryId, relations, now)
    return relations.size
  }

  fun currentContentGeneration(bookId: String): String? = bookRepository.findByIdOrNull(bookId)?.let(::sourceSnapshot)?.contentGeneration

  fun currentSourceIdentity(bookId: String): DedupSourceIdentity? {
    val book = bookRepository.findByIdOrNull(bookId) ?: return null
    val source = sourceSnapshot(book) ?: return null
    return DedupSourceIdentity(
      bookId = book.id,
      seriesId = book.seriesId,
      libraryId = book.libraryId,
      contentGeneration = source.contentGeneration,
      coverGeneration = source.coverGeneration,
      metadataGeneration = source.metadataGeneration,
      seriesScopeRevision = source.scopeRevision,
      pageCount = source.pageCount,
      archiveHashState = source.archiveHashState,
      archiveHash = source.archiveHash,
    )
  }

  fun persistArchiveIdentity(
    bookId: String,
    identity: DedupStrongFileIdentity,
    now: LocalDateTime = LocalDateTime.now(),
  ) {
    val book = requireNotNull(bookRepository.findByIdOrNull(bookId)) { "Book no longer exists" }
    val expectedPath =
      book.path
        .toAbsolutePath()
        .normalize()
        .toString()
    check(identity.path == expectedPath) { "Book path changed during archive hashing" }
    check(identity.size == book.fileSize) { "Archive size no longer matches Komga" }
    val feature = requireNotNull(dedupRepository.findFeature(bookId)) { "Dedup feature is unavailable" }
    val generation = dedupContentGeneration(identity.size, identity.archiveHash)
    dedupRepository.saveFeature(
      feature.copy(
        sourceContentGeneration = generation,
        pageState = if (feature.sourceContentGeneration == generation) feature.pageState else DedupFeatureState.WAITING,
        archiveHash = identity.archiveHash,
        archiveHashPath = identity.path,
        archiveHashSize = identity.size,
        archiveHashSchemaVersion = DEDUP_ARCHIVE_HASH_SCHEMA_VERSION,
        archiveHashDate = now,
        lastModifiedDate = now,
      ),
    )
  }

  fun currentSourceIdentities(libraryId: String): List<DedupSourceIdentity> = eligibleBooks(libraryId).mapNotNull { currentSourceIdentity(it.id) }

  private fun eligibleBooks(libraryId: String): List<Book> {
    val active =
      bookRepository
        .findAll()
        .filter { it.libraryId == libraryId && it.deletedDate == null && it.url.path.endsWith(".cbz", ignoreCase = true) }
    val activeBySeries = active.groupBy { it.seriesId }
    return active.filter { activeBySeries[it.seriesId].orEmpty().size == 1 }
  }

  private fun sourceSnapshot(book: Book): CoverSourceSnapshot? {
    if (book.deletedDate != null || !book.url.path.endsWith(".cbz", ignoreCase = true)) return null
    val activeIds =
      bookRepository
        .findAllBySeriesId(book.seriesId)
        .filter { it.deletedDate == null }
        .map { it.id }
        .sorted()
    if (activeIds != listOf(book.id)) return null
    val thumbnail = thumbnailBookRepository.findSelectedByBookIdOrNull(book.id)
    val feature = dedupRepository.findFeature(book.id)
    val expectedPath =
      book.path
        .toAbsolutePath()
        .normalize()
        .toString()
    val archiveHashState =
      when {
        feature?.archiveHash.isNullOrBlank() -> DedupArchiveHashState.MISSING
        feature.archiveHashPath != expectedPath || feature.archiveHashSize != book.fileSize ||
          feature.archiveHashSchemaVersion != DEDUP_ARCHIVE_HASH_SCHEMA_VERSION -> DedupArchiveHashState.STALE
        else -> DedupArchiveHashState.READY
      }
    val archiveHash = feature?.archiveHash?.takeIf { archiveHashState == DedupArchiveHashState.READY }
    val coverIdentity =
      when {
        thumbnail == null -> "missing"
        thumbnail.thumbnail != null -> "${thumbnail.id}|${stableHash(thumbnail.thumbnail)}"
        else -> "${thumbnail.id}|${thumbnail.url}|${thumbnail.fileSize}"
      }
    return CoverSourceSnapshot(
      contentGeneration = dedupContentGeneration(book.fileSize, archiveHash),
      coverGeneration = stableHash(coverIdentity),
      metadataGeneration = stableHash("${book.name}|${book.seriesId}"),
      scopeRevision = stableHash("${book.seriesId}|${activeIds.joinToString() }"),
      pageCount = mediaRepository.findByIdOrNull(book.id)?.pages?.size,
      thumbnail = thumbnail,
      archiveHashState = archiveHashState,
      archiveHash = archiveHash,
      archiveHashPath = feature?.archiveHashPath,
      archiveHashSize = feature?.archiveHashSize,
      archiveHashSchemaVersion = feature?.archiveHashSchemaVersion,
      archiveHashDate = feature?.archiveHashDate,
    )
  }

  private fun exactPairs(libraryId: String): Set<Pair<String, String>> =
    exactDuplicateBookRepository
      .findAllExactDuplicates(libraryId, includeDeleted = false)
      .groupBy { it.fileHash to it.fileSize }
      .values
      .flatMap { books ->
        val sorted = books.map { it.id }.sorted()
        sorted.flatMapIndexed { index, left -> sorted.drop(index + 1).map { right -> left to right } }
      }.toSet()

  private fun stableHash(value: String): String = stableHash(value.toByteArray(StandardCharsets.UTF_8))

  private fun stableHash(value: ByteArray): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value)
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
      .take(32)

  private data class CoverSourceSnapshot(
    val contentGeneration: String,
    val coverGeneration: String,
    val metadataGeneration: String,
    val scopeRevision: String,
    val pageCount: Int?,
    val thumbnail: ThumbnailBook?,
    val archiveHashState: DedupArchiveHashState,
    val archiveHash: String?,
    val archiveHashPath: String?,
    val archiveHashSize: Long?,
    val archiveHashSchemaVersion: Int?,
    val archiveHashDate: LocalDateTime?,
  ) {
    fun matches(other: CoverSourceSnapshot): Boolean =
      contentGeneration == other.contentGeneration &&
        coverGeneration == other.coverGeneration &&
        metadataGeneration == other.metadataGeneration &&
        scopeRevision == other.scopeRevision
  }
}
