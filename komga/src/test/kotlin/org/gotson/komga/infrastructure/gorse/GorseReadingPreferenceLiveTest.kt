package org.gotson.komga.infrastructure.gorse

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DomainEvent
import org.gotson.komga.domain.model.Media
import org.gotson.komga.domain.model.ReadProgress
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.domain.persistence.ReadProgressRepository
import org.gotson.komga.interfaces.api.rest.dto.GorsePreference
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.parallel.ResourceLock
import java.time.Instant
import java.time.LocalDateTime
import java.util.TimeZone
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "KOMGA_TEST_GORSE_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
@ResourceLock("default-time-zone")
class GorseReadingPreferenceLiveTest {
  @Test
  fun `isolated Gorse persists explicit preference and read together`() {
    val settings = mockk<GorseSettingsProvider>()
    every { settings.apiUrl } returns System.getenv("KOMGA_TEST_GORSE_URL")
    every { settings.apiKey } returns "local-validation-only"
    every { settings.feedbackType } returns "read"
    every { settings.positiveFeedbackType } returns "favorite"
    every { settings.negativeFeedbackType } returns "dislike"
    val client = GorseClient(settings)
    val service = GorsePreferenceService(client, settings)
    val userId = "komga-test-${UUID.randomUUID()}"
    val seriesId = "series-${UUID.randomUUID()}"
    var completions = 0

    for (preference in listOf(GorsePreference.LIKE, GorsePreference.DISLIKE)) {
      service.setPreferenceAndComplete(userId, seriesId, preference) { completions++ }
      assertThat(client.getFeedbackChecked(userId, seriesId).map { it.FeedbackType })
        .containsExactlyInAnyOrder(if (preference == GorsePreference.LIKE) "favorite" else "dislike", "read")
      assertThat(client.getUserFeedbackByTypeChecked(userId, "read").map { it.ItemId }).contains(seriesId)
    }
    service.setPreference(userId, seriesId, GorsePreference.NONE)
    assertThat(client.getFeedbackChecked(userId, seriesId).map { it.FeedbackType }).containsExactly("read")
    assertThat(completions).isEqualTo(2)
  }

  @Test
  fun `Shanghai reading events and bulk sync are immediately effective in Gorse`() {
    val originalZone = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
      val settings = mockk<GorseSettingsProvider>()
      every { settings.apiUrl } returns System.getenv("KOMGA_TEST_GORSE_URL")
      every { settings.apiKey } returns "local-validation-only"
      every { settings.enabled } returns true
      every { settings.readThreshold } returns 0.5
      every { settings.feedbackType } returns "read"
      val client = GorseClient(settings)
      val book = makeBook("time check", seriesId = "series-${UUID.randomUUID()}")
      val progress = ReadProgress(book.id, "reader-${UUID.randomUUID()}", 1, true, LocalDateTime.now().minusSeconds(2))
      val books = mockk<BookRepository>()
      val media = mockk<MediaRepository>()
      val progresses = mockk<ReadProgressRepository>()
      every { books.findByIdOrNull(book.id) } returns book
      every { media.findByIdOrNull(book.id) } returns Media(bookId = book.id, pageCount = 1)
      every { progresses.findAll() } returns listOf(progress)
      val listener = GorseEventListener(client, settings, mockk(), books, mockk(), progresses, media, mockk())

      listener.handleEvent(DomainEvent.ReadProgressChanged(progress))
      val timestamp = client.getFeedbackChecked(progress.userId, book.seriesId).single().Timestamp
      assertThat(Instant.parse(timestamp)).isBeforeOrEqualTo(Instant.now())
      assertThat(client.getUserFeedbackByTypeChecked(progress.userId, "read").map { it.ItemId }).contains(book.seriesId)

      client.deleteFeedbackChecked("read", progress.userId, book.seriesId)
      assertThat(listener.syncAllFeedback()).isEqualTo(1)
      assertThat(client.getUserFeedbackByTypeChecked(progress.userId, "read").map { it.ItemId }).contains(book.seriesId)
    } finally {
      TimeZone.setDefault(originalZone)
    }
  }
}
