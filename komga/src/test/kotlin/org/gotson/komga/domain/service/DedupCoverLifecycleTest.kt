package org.gotson.komga.domain.service

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupFeatureState
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupOverride
import org.gotson.komga.domain.model.DedupOverrideType
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupReviewCaseOrigin
import org.gotson.komga.domain.model.Dimension
import org.gotson.komga.domain.model.ThumbnailBook
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.ThumbnailBookRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.imageio.ImageIO

@SpringBootTest
class DedupCoverLifecycleTest(
  @Autowired private val lifecycle: DedupCoverLifecycle,
  @Autowired private val dedupRepository: DedupRepository,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val thumbnailRepository: ThumbnailBookRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val bookLifecycle: BookLifecycle,
  @Autowired private val libraryLifecycle: LibraryLifecycle,
) {
  private val library = makeLibrary("dedup-cover")

  @MockkBean
  private lateinit var eventPublisher: ApplicationEventPublisher

  @BeforeEach
  fun setup() {
    every { eventPublisher.publishEvent(any()) } just Runs
    libraryRepository.insert(library)
    dedupRepository.saveLibrarySettings(DedupLibrarySettings(library.id, enabled = true))
  }

  @AfterEach
  fun cleanup() {
    dedupRepository.deleteAllDedupData()
    bookLifecycle.deleteMany(bookRepository.findAll())
    libraryRepository.findAll().forEach(libraryLifecycle::deleteLibrary)
  }

  @Test
  fun `local thumbnail blobs create visual pair cases while URL covers stay deferred`() {
    val cover = coverBytes()
    val books =
      (1..3).map { index ->
        val series = seriesLifecycle.createSeries(makeSeries("Series $index", library.id))
        val book =
          makeBook(
            "Book $index",
            libraryId = library.id,
            seriesId = series.id,
            url = URL("file:/book-$index.cbz"),
          ).copy(fileHash = "different-$index", fileSize = 100L + index, oneshot = true)
        seriesLifecycle.addBooks(series, listOf(book))
        book
      }

    books.take(2).forEach { book ->
      thumbnailRepository.insert(
        ThumbnailBook(
          thumbnail = cover,
          selected = true,
          type = ThumbnailBook.Type.GENERATED,
          mediaType = "image/png",
          fileSize = cover.size.toLong(),
          dimension = Dimension(120, 180),
          bookId = book.id,
        ),
      )
    }
    thumbnailRepository.insert(
      ThumbnailBook(
        url = URL("file:/remote-cover.jpg"),
        selected = true,
        type = ThumbnailBook.Type.SIDECAR,
        mediaType = "image/jpeg",
        fileSize = 123,
        dimension = Dimension(120, 180),
        bookId = books[2].id,
      ),
    )

    val dirty = lifecycle.findDirtyBookIds(library.id)
    assertThat(dirty).containsExactlyInAnyOrderElementsOf(books.map { it.id })
    dirty.forEach(lifecycle::computeCover)

    assertThat(dedupRepository.findFeature(books[0].id)?.coverState).isEqualTo(DedupFeatureState.READY)
    assertThat(dedupRepository.findFeature(books[2].id))
      .extracting("coverState", "coverSource", "coverHash")
      .containsExactly(DedupFeatureState.WAITING, "REMOTE_COVER_DEFERRED", null)

    assertThat(lifecycle.rebuildCandidates(library.id)).isEqualTo(1)
    val reviewCase = dedupRepository.findReviewCases(library.id, DedupReviewCaseOrigin.COVER_SIMILARITY).single()
    val relation = dedupRepository.findRelation(books[0].id, books[1].id)!!
    assertThat(reviewCase.memberBookIds).containsExactlyInAnyOrder(books[0].id, books[1].id)
    assertThat(relation.type).isEqualTo(DedupRelationType.VISUALLY_SIMILAR)
    assertThat(relation.coverDistance).isZero()

    assertThat(dedupRepository.setReviewCaseKeeper(reviewCase.id, reviewCase.revision, books[0].id)).isTrue
    val withKeeper = dedupRepository.findReviewCase(reviewCase.id)!!
    assertThat(withKeeper.suggestedKeeperBookId).isEqualTo(books[0].id)
    assertThat(
      dedupRepository.applyOverride(
        caseId = reviewCase.id,
        expectedRevision = withKeeper.revision,
        override = DedupOverride(id = "protect", type = DedupOverrideType.PROTECTED, bookId = books[0].id, actorId = "admin"),
        newStatus = withKeeper.status,
      ),
    ).isTrue
    assertThat(dedupRepository.findProtectedBookIds(setOf(books[0].id, books[1].id))).containsExactly(books[0].id)
  }

  private fun coverBytes(): ByteArray {
    val image = BufferedImage(120, 180, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, 120, 180)
    graphics.color = Color.BLACK
    graphics.fillRect(30, 40, 60, 100)
    graphics.dispose()
    return ByteArrayOutputStream().use {
      ImageIO.write(image, "png", it)
      it.toByteArray()
    }
  }
}
