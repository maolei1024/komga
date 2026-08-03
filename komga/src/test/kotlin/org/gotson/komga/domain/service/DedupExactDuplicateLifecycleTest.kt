package org.gotson.komga.domain.service

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupReviewCaseOrigin
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import java.net.URL

@SpringBootTest
class DedupExactDuplicateLifecycleTest(
  @Autowired private val lifecycle: DedupExactDuplicateLifecycle,
  @Autowired private val dedupRepository: DedupRepository,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val bookLifecycle: BookLifecycle,
  @Autowired private val libraryLifecycle: LibraryLifecycle,
) {
  private val library = makeLibrary("dedup-exact")

  @MockkBean
  private lateinit var eventPublisher: ApplicationEventPublisher

  @BeforeEach
  fun setup() {
    every { eventPublisher.publishEvent(any()) } just Runs
    libraryRepository.insert(library)
  }

  @AfterEach
  fun cleanup() {
    dedupRepository.deleteAllDedupData()
    bookLifecycle.deleteMany(bookRepository.findAll())
    libraryRepository.findAll().forEach(libraryLifecycle::deleteLibrary)
  }

  @Test
  fun `exact CBZ duplicates form one stable review case without admitting a size mismatch`() {
    val series =
      (1..3).map {
        seriesLifecycle.createSeries(makeSeries("Series $it", library.id))
      }
    seriesLifecycle.addBooks(
      series[0],
      listOf(
        makeBook("Book 1", libraryId = library.id, seriesId = series[0].id, url = URL("file:/book-1.cbz"))
          .copy(fileHash = "same-hash", fileSize = 100, oneshot = true),
      ),
    )
    seriesLifecycle.addBooks(
      series[1],
      listOf(
        makeBook("Book 2", libraryId = library.id, seriesId = series[1].id, url = URL("file:/book-2.cbz"))
          .copy(fileHash = "same-hash", fileSize = 100, oneshot = true),
      ),
    )
    seriesLifecycle.addBooks(
      series[2],
      listOf(
        makeBook("Different size", libraryId = library.id, seriesId = series[2].id, url = URL("file:/book-3.cbz"))
          .copy(fileHash = "same-hash", fileSize = 101, oneshot = true),
      ),
    )

    assertThat(lifecycle.reconcileLibrary(library.id)).isEqualTo(1)
    val first = dedupRepository.findReviewCases(library.id, DedupReviewCaseOrigin.EXACT_FILE).single()
    assertThat(first.memberBookIds).hasSize(2)
    assertThat(first.memberBookIds.mapNotNull(bookRepository::findByIdOrNull).map { it.name })
      .containsExactlyInAnyOrder("Book 1", "Book 2")
    assertThat(first.revision).isEqualTo(1)

    assertThat(lifecycle.reconcileLibrary(library.id)).isEqualTo(1)
    val unchanged = dedupRepository.findReviewCase(first.id)!!
    assertThat(unchanged.revision).isEqualTo(1)
    assertThat(unchanged.lastModifiedDate).isEqualTo(first.lastModifiedDate)
  }
}
