package org.gotson.komga.domain.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupFeatureState
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.ExactDuplicateBookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ThumbnailBookRepository
import org.gotson.komga.infrastructure.dedup.CoverNeighbor
import org.gotson.komga.infrastructure.dedup.CoverPerceptualHasher
import org.gotson.komga.infrastructure.dedup.CoverSimilarityIndex
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DedupCoverLifecycleTest {
  @Test
  fun `cover candidate refresh preserves current deep evidence and adds current cover distance`() {
    val books = mockk<BookRepository>()
    val media = mockk<MediaRepository>()
    val thumbnails = mockk<ThumbnailBookRepository>()
    val exactBooks = mockk<ExactDuplicateBookRepository>()
    val dedup = mockk<DedupRepository>()
    val hasher = mockk<CoverPerceptualHasher>()
    val index = mockk<CoverSimilarityIndex>()
    val lifecycle = DedupCoverLifecycle(books, media, thumbnails, exactBooks, dedup, hasher, index, jacksonObjectMapper())
    val features = listOf(feature("A"), feature("B"))
    val deep =
      DedupRelation(
        "deep",
        "library",
        "A",
        "B",
        "content-A",
        "content-B",
        type = DedupRelationType.EXACT_PAGE_SEQUENCE,
        featureSchemaVersion = DedupDeepVerificationLifecycle.PAGE_FEATURE_SCHEMA_VERSION,
        classifierRuleVersion = DedupDeepVerificationLifecycle.CLASSIFIER_RULE_VERSION,
      )
    every { dedup.findLibrarySettings("library") } returns DedupLibrarySettings("library")
    every { dedup.findReadyCoverFeatures("library") } returns features
    every { index.replaceLibrary("library", features, 15) } just Runs
    every { index.findAllNeighbors("library", 20) } returns listOf(CoverNeighbor("A", "B", 0))
    every { exactBooks.findAllExactDuplicates("library", false) } returns emptyList()
    every { dedup.findRelation("A", "B") } returns deep
    val captured = slot<Collection<DedupRelation>>()
    every { dedup.replaceCoverRelations("library", capture(captured), any()) } just Runs

    assertThat(lifecycle.rebuildCandidates("library")).isEqualTo(1)
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
