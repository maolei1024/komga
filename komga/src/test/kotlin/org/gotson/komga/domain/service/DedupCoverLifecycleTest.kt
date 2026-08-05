package org.gotson.komga.domain.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DEDUP_ARCHIVE_HASH_SCHEMA_VERSION
import org.gotson.komga.domain.model.DedupArchiveHashState
import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupFeatureState
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ThumbnailBookRepository
import org.gotson.komga.infrastructure.dedup.CoverNeighbor
import org.gotson.komga.infrastructure.dedup.CoverPerceptualHasher
import org.gotson.komga.infrastructure.dedup.CoverSimilarityIndex
import org.junit.jupiter.api.Test
import java.net.URL
import java.time.LocalDateTime

class DedupCoverLifecycleTest {
  @Test
  fun `Book audit time is ignored while an unhashed file timestamp changes content generation`() {
    val books = mockk<BookRepository>()
    val media = mockk<MediaRepository>()
    val thumbnails = mockk<ThumbnailBookRepository>()
    val dedup = mockk<DedupRepository>()
    var book =
      Book(
        name = "book.cbz",
        url = URL("file:/tmp/book.cbz"),
        fileLastModified = LocalDateTime.MIN,
        fileSize = 42,
        id = "book",
        seriesId = "series",
        libraryId = "library",
      )
    every { books.findByIdOrNull("book") } answers { book }
    every { books.findAllBySeriesId("series") } answers { listOf(book) }
    every { thumbnails.findSelectedByBookIdOrNull("book") } returns null
    var feature: DedupFeature? = null
    every { dedup.findFeature("book") } answers { feature }
    every { dedup.saveFeature(any()) } answers { feature = firstArg() }
    every { media.findByIdOrNull("book") } returns null
    val lifecycle =
      DedupCoverLifecycle(
        books,
        media,
        thumbnails,
        dedup,
        mockk(),
        mockk(),
        jacksonObjectMapper(),
      )

    val before = lifecycle.currentSourceIdentity("book")!!
    book = book.copy(lastModifiedDate = LocalDateTime.MAX)
    val after = lifecycle.currentSourceIdentity("book")!!

    assertThat(after.contentGeneration).isEqualTo(before.contentGeneration)
    assertThat(after.coverGeneration).isEqualTo(before.coverGeneration)
    assertThat(after.metadataGeneration).isEqualTo(before.metadataGeneration)

    book = book.copy(fileLastModified = LocalDateTime.MAX)
    assertThat(lifecycle.currentSourceIdentity("book")!!.contentGeneration).isNotEqualTo(before.contentGeneration)
    book = book.copy(fileLastModified = LocalDateTime.MIN)

    feature = feature("book").copy(pageState = DedupFeatureState.READY)
    lifecycle.persistArchiveIdentity("book", DedupStrongFileIdentity("/tmp/book.cbz", 42, "archive-hash"))
    assertThat(feature!!.sourceContentGeneration).isEqualTo("dedup-v2:42:${LocalDateTime.MIN}:archive-hash")
    assertThat(feature!!.pageState).isEqualTo(DedupFeatureState.WAITING)
    assertThat(feature!!.archiveHashDate).isNotNull()

    val readyFeature = feature!!
    feature = readyFeature
    val ready = lifecycle.currentSourceIdentity("book")!!
    assertThat(ready.archiveHashState).isEqualTo(DedupArchiveHashState.READY)
    assertThat(ready.archiveHash).isEqualTo("archive-hash")
    assertThat(ready.contentGeneration).isNotEqualTo(before.contentGeneration)

    book = book.copy(fileHash = "changed-file-hash")
    val modified = lifecycle.currentSourceIdentity("book")!!
    assertThat(modified.archiveHashState).isEqualTo(DedupArchiveHashState.STALE)
    assertThat(modified.archiveHash).isNull()
    assertThat(modified.contentGeneration).isNotEqualTo(ready.contentGeneration)

    book = book.copy(fileHash = "")
    listOf(
      readyFeature.copy(archiveHashPath = "/tmp/moved.cbz"),
      readyFeature.copy(archiveHashSize = 43),
      readyFeature.copy(archiveHashSchemaVersion = DEDUP_ARCHIVE_HASH_SCHEMA_VERSION + 1),
    ).forEach { staleFeature ->
      feature = staleFeature
      val stale = lifecycle.currentSourceIdentity("book")!!
      assertThat(stale.archiveHashState).isEqualTo(DedupArchiveHashState.STALE)
      assertThat(stale.archiveHash).isNull()
      assertThat(stale.contentGeneration).isEqualTo(before.contentGeneration)
    }
  }

  @Test
  fun `cover candidate refresh preserves current deep evidence and adds current cover distance`() {
    val books = mockk<BookRepository>()
    val media = mockk<MediaRepository>()
    val thumbnails = mockk<ThumbnailBookRepository>()
    val dedup = mockk<DedupRepository>()
    val hasher = mockk<CoverPerceptualHasher>()
    val index = mockk<CoverSimilarityIndex>()
    val lifecycle = DedupCoverLifecycle(books, media, thumbnails, dedup, hasher, index, jacksonObjectMapper())
    val features = listOf(feature("A"), feature("B"))
    val deep =
      DedupRelation(
        "deep",
        "library",
        "A",
        "B",
        "content-A",
        "content-B",
        lowCoverGeneration = "cover-A",
        highCoverGeneration = "cover-B",
        lowMetadataGeneration = "metadata-A",
        highMetadataGeneration = "metadata-B",
        type = DedupRelationType.EXACT_PAGE_SEQUENCE,
        featureSchemaVersion = DedupDeepVerificationLifecycle.PAGE_FEATURE_SCHEMA_VERSION,
        classifierRuleVersion = DedupDeepVerificationLifecycle.CLASSIFIER_RULE_VERSION,
      )
    every { dedup.findLibrarySettings("library") } returns DedupLibrarySettings("library")
    every { dedup.findFeature("A") } returns features.first()
    every { dedup.findFeatures(setOf("A", "B")) } returns features
    every { index.findNeighbors("library", "A", 20) } returns listOf(CoverNeighbor("A", "B", 0))
    every { dedup.findRelation("A", "B") } returns deep
    val captured = slot<Collection<DedupRelation>>()
    every { dedup.replaceCoverRelationsForBook("A", capture(captured), any()) } just Runs

    assertThat(lifecycle.refreshCandidatesForBook("A")).isEmpty()
    assertThat(captured.captured.single())
      .extracting("id", "type", "coverDistance", "lowCoverGeneration", "highCoverGeneration")
      .containsExactly("deep", DedupRelationType.EXACT_PAGE_SEQUENCE, 0, "cover-A", "cover-B")
  }

  private fun feature(id: String) =
    DedupFeature(
      id,
      "series-$id",
      "library",
      "content-$id",
      "cover-$id",
      "metadata-$id",
      "scope-$id",
      DedupCoverLifecycle.FEATURE_SCHEMA_VERSION,
      DedupFeatureState.READY,
      coverSource = "BOOK_THUMBNAIL_BLOB",
      coverHash = ByteArray(32),
      coverQuality = 100,
      pageCount = 10,
      analyzedDate = LocalDateTime.MIN,
      lastModifiedDate = LocalDateTime.MIN,
    )
}
