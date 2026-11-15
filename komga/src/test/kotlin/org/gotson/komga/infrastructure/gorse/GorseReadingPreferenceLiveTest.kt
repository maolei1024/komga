package org.gotson.komga.infrastructure.gorse

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.interfaces.api.rest.dto.GorsePreference
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID

@EnabledIfEnvironmentVariable(named = "KOMGA_TEST_GORSE_URL", matches = "http://127\\.0\\.0\\.1:[0-9]+")
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
}
