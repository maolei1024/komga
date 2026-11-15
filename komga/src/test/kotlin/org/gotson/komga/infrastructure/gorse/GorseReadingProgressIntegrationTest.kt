package org.gotson.komga.infrastructure.gorse

import com.ninjasquad.springmockk.MockkBean
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.KomgaUserRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.domain.service.SeriesLifecycle
import org.gotson.komga.infrastructure.security.KomgaPrincipal
import org.gotson.komga.interfaces.api.persistence.SeriesDtoRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest
@AutoConfigureMockMvc
class GorseReadingProgressIntegrationTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val libraries: LibraryRepository,
  @Autowired private val users: KomgaUserRepository,
  @Autowired private val books: BookRepository,
  @Autowired private val media: MediaRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val seriesDtos: SeriesDtoRepository,
) {
  @MockkBean(relaxed = true)
  private lateinit var client: GorseClient

  @SpykBean
  private lateinit var settings: GorseSettingsProvider

  @SpykBean
  private lateinit var progresses: ReadProgressRepository

  private val reader = KomgaUser("gorse-reader@example.org", "password")
  private val library = makeLibrary()
  private val series = makeSeries("gorse series", libraryId = library.id)
  private val feedback = mutableListOf<GorseFeedback>()
  private var bookIds = emptyList<String>()

  @BeforeEach
  fun setup() {
    feedback.clear()
    every { settings.enabled } returns false
    libraries.insert(library)
    users.insert(reader)
    seriesLifecycle.createSeries(series)
    seriesLifecycle.addBooks(series, listOf(makeBook("first", libraryId = library.id), makeBook("second", libraryId = library.id)))
    val seriesBooks = books.findAllBySeriesId(series.id).sortedBy { it.name }
    bookIds = seriesBooks.map { it.id }
    seriesBooks.forEachIndexed { index, book ->
      media.update(media.findById(book.id).copy(pageCount = (index + 1) * 12))
    }
    every { settings.enabled } returns true
    every { settings.feedbackType } returns "read"
    every { settings.positiveFeedbackType } returns "like"
    every { settings.negativeFeedbackType } returns "dislike"
    every { client.getFeedbackChecked(reader.id, series.id) } answers { feedback.toList() }
    every { client.deleteFeedbackChecked(any(), reader.id, series.id) } answers {
      feedback.removeAll { it.FeedbackType == firstArg<String>() }
      Unit
    }
    every { client.insertFeedbackChecked(any()) } answers { store(firstArg()) }
    every { client.insertFeedback(any()) } answers { store(firstArg()) }
  }

  @AfterEach
  fun cleanup() {
    every { settings.enabled } returns false
    seriesLifecycle.deleteMany(listOf(series))
    users.delete(reader.id)
    libraries.delete(library.id)
  }

  @Test
  fun `book feedback completes every current book and aggregates series progress`() {
    progresses.save(ReadProgress(bookIds.first(), reader.id, 3, false))
    update("book/${bookIds.first()}", "LIKE", 200)
    assertCompleted()
    assertThat(feedback.map { it.FeedbackType }).containsExactlyInAnyOrder("like", "read")

    update("series/${series.id}", "DISLIKE", 200)
    assertCompleted()
    assertThat(feedback.map { it.FeedbackType }).containsExactlyInAnyOrder("dislike", "read")
    update("series/${series.id}", "NONE", 200)
    assertCompleted()
    assertThat(feedback.map { it.FeedbackType }).containsExactly("read")
  }

  @Test
  fun `failed read delivery leaves progress unchanged and retry succeeds`() {
    every { client.insertFeedbackChecked(any()) } answers {
      val incoming = firstArg<List<GorseFeedback>>()
      if (incoming.any { it.FeedbackType == "read" }) error("Gorse unavailable")
      store(incoming)
    }
    update("series/${series.id}", "LIKE", 502)
    assertThat(progresses.findAllByUserId(reader.id)).isEmpty()
    assertThat(feedback).isEmpty()

    every { client.insertFeedbackChecked(any()) } answers { store(firstArg()) }
    update("series/${series.id}", "LIKE", 200)
    assertCompleted()
  }

  @Test
  fun `database save failure restores original Gorse feedback`() {
    every { progresses.save(any<Collection<ReadProgress>>()) } throws IllegalStateException("database unavailable")
    update("series/${series.id}", "DISLIKE", 502)
    assertThat(progresses.findAllByUserId(reader.id)).isEmpty()
    assertThat(feedback).isEmpty()
  }

  @Test
  fun `library restrictions prevent feedback and completion through both routes`() {
    val restricted = reader.copy(sharedAllLibraries = false, sharedLibrariesIds = emptySet())
    for (target in listOf("series/${series.id}", "book/${bookIds.first()}")) {
      mockMvc
        .perform(
          put("/api/v1/gorse/preference/$target")
            .with(user(KomgaPrincipal(restricted)))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"preference":"LIKE"}"""),
        ).andExpect(status().isForbidden())
    }
    assertThat(progresses.findAllByUserId(reader.id)).isEmpty()
    verify(exactly = 0) { client.insertFeedbackChecked(any()) }
  }

  private fun update(
    target: String,
    preference: String,
    expectedStatus: Int,
  ) {
    mockMvc
      .perform(
        put("/api/v1/gorse/preference/$target")
          .with(user(KomgaPrincipal(reader)))
          .contentType(MediaType.APPLICATION_JSON)
          .content("""{"preference":"$preference"}"""),
      ).andExpect(status().`is`(expectedStatus))
  }

  private fun store(incoming: List<GorseFeedback>) {
    feedback.removeAll { old -> incoming.any { it.FeedbackType == old.FeedbackType } }
    feedback += incoming
  }

  private fun assertCompleted() {
    val saved = progresses.findAllByUserId(reader.id)
    assertThat(saved).hasSize(2)
    saved.forEach {
      assertThat(it.completed).isTrue()
      assertThat(it.page).isEqualTo(media.findById(it.bookId).pageCount)
    }
    assertThat(seriesDtos.findByIdOrNull(series.id, reader.id)!!.booksReadCount).isEqualTo(2)
  }
}
