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
  private val dedupRepository: DedupRepository,
  private val coverHasher: CoverPerceptualHasher,
  private val coverIndex: CoverSimilarityIndex,
  private val objectMapper: ObjectMapper,
) {
  companion object {
    const val FEATURE_SCHEMA_VERSION = 2
  }

  fun rebuildIndex(libraryId: String) {
    val settings = dedupRepository.findLibrarySettings(libraryId) ?: return
    coverIndex.replaceLibrary(libraryId, dedupRepository.findReadyCoverFeatures(libraryId), settings.coverCandidateDistance)
  }

  fun computeCover(bookId: String): DedupFeature? {
    val book = bookRepository.findByIdOrNull(bookId) ?: return null
    val before = sourceSnapshot(book) ?: return null
    val now = LocalDateTime.now()
    val bytes = before.thumbnail?.thumbnail
    val hashResult = bytes?.let(coverHasher::hash)
    val after = bookRepository.findByIdOrNull(bookId)?.let(::sourceSnapshot)
    check(after != null && before.matches(after)) { "Book or selected thumbnail changed during cover analysis" }

    val feature =
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
      )
    dedupRepository.saveFeature(feature)
    dedupRepository.findLibrarySettings(book.libraryId)?.let { settings ->
      coverIndex.upsertFeature(book.libraryId, feature, settings.coverCandidateDistance)
    }
    return feature
  }

  fun refreshCandidatesForBook(bookId: String): List<Pair<String, String>> {
    val target = dedupRepository.findFeature(bookId) ?: return emptyList()
    val settings = dedupRepository.findLibrarySettings(target.libraryId) ?: return emptyList()
    val neighbors = coverIndex.findNeighbors(target.libraryId, bookId, settings.coverTopK)
    val features = dedupRepository.findFeatures(neighbors.flatMap { listOf(it.bookLowId, it.bookHighId) }.toSet()).associateBy { it.bookId }
    val now = LocalDateTime.now()
    val relations =
      neighbors
        .mapNotNull { neighbor ->
          val low = features[neighbor.bookLowId] ?: return@mapNotNull null
          val high = features[neighbor.bookHighId] ?: return@mapNotNull null
          val pairIdentity = "${neighbor.bookLowId}|${neighbor.bookHighId}"
          val candidateRelation =
            DedupRelation(
              id = "cover-relation-${stableHash(pairIdentity)}",
              libraryId = target.libraryId,
              bookLowId = neighbor.bookLowId,
              bookHighId = neighbor.bookHighId,
              lowContentGeneration = low.sourceContentGeneration,
              highContentGeneration = high.sourceContentGeneration,
              type = DedupRelationType.COVER_CANDIDATE,
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
          val identities =
            mapOf(
              low.bookId to low.toSourceIdentity(),
              high.bookId to high.toSourceIdentity(),
            )
          val relation =
            currentRelation
              ?.takeIf {
                it.isCurrent(identities)
              }?.copy(
                coverDistance = neighbor.distance,
                lastModifiedDate = now,
              ) ?: candidateRelation
          relation
        }

    dedupRepository.replaceCoverRelationsForBook(bookId, relations, now)
    return relations
      .filterNot { relation ->
        val identities =
          buildMap {
            features[relation.bookLowId]?.let { put(it.bookId, it.toSourceIdentity()) }
            features[relation.bookHighId]?.let { put(it.bookId, it.toSourceIdentity()) }
          }
        relation.isCurrent(identities)
      }.map { it.bookLowId to it.bookHighId }
  }

  fun cleanupBook(
    libraryId: String,
    bookId: String,
  ) {
    dedupRepository.deleteBookData(bookId)
    val threshold = dedupRepository.findLibrarySettings(libraryId)?.coverCandidateDistance ?: 15
    coverIndex.removeFeature(libraryId, bookId, threshold)
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
    val generation = dedupContentGeneration(identity.size, identity.archiveHash, book.contentFingerprint())
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

  fun currentSourceIdentities(libraryId: String): List<DedupSourceIdentity> = dedupRepository.findFeaturesByLibrary(libraryId).mapNotNull { currentSourceIdentity(it.bookId) }

  private fun sourceSnapshot(book: Book): CoverSourceSnapshot? {
    if (book.deletedDate != null || !book.url.path.endsWith(".cbz", ignoreCase = true)) return null
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
          feature.archiveHashSchemaVersion != DEDUP_ARCHIVE_HASH_SCHEMA_VERSION ||
          feature.sourceContentGeneration != dedupContentGeneration(book.fileSize, feature.archiveHash, book.contentFingerprint()) -> DedupArchiveHashState.STALE
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
      contentGeneration = dedupContentGeneration(book.fileSize, archiveHash, book.contentFingerprint()),
      coverGeneration = stableHash(coverIdentity),
      metadataGeneration = stableHash("${book.name}|${book.seriesId}"),
      scopeRevision = stableHash(book.seriesId),
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

  private fun stableHash(value: String): String = stableHash(value.toByteArray(StandardCharsets.UTF_8))

  private fun Book.contentFingerprint(): String = fileHash.ifBlank { fileLastModified.toString() }

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

  private fun DedupFeature.toSourceIdentity() =
    DedupSourceIdentity(
      bookId = bookId,
      seriesId = seriesId,
      libraryId = libraryId,
      contentGeneration = sourceContentGeneration,
      coverGeneration = sourceCoverGeneration,
      metadataGeneration = sourceMetadataGeneration,
      seriesScopeRevision = seriesScopeRevision,
      pageCount = pageCount,
      archiveHashState =
        if (!archiveHash.isNullOrBlank() && archiveHashSchemaVersion == DEDUP_ARCHIVE_HASH_SCHEMA_VERSION) DedupArchiveHashState.READY else DedupArchiveHashState.MISSING,
      archiveHash = archiveHash,
    )
}
