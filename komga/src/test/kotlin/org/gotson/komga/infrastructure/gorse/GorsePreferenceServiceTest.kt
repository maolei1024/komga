package org.gotson.komga.infrastructure.gorse

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.gotson.komga.interfaces.api.rest.dto.GorsePreference
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GorsePreferenceServiceTest {
  private val client = mockk<GorseClient>()
  private val settings = mockk<GorseSettingsProvider>()
  private lateinit var service: GorsePreferenceService
  private val stored = mutableListOf<GorseFeedback>()

  @BeforeEach
  fun setup() {
    clearMocks(client, settings)
    stored.clear()
    every { settings.feedbackType } returns "read"
    every { settings.positiveFeedbackType } returns "like"
    every { settings.negativeFeedbackType } returns "dislike"
    every { client.getFeedbackChecked("user", "series") } answers { stored.toList() }
    every { client.deleteFeedbackChecked(any(), "user", "series") } answers {
      stored.removeAll { it.FeedbackType == firstArg<String>() }
      Unit
    }
    val inserted = slot<List<GorseFeedback>>()
    every { client.insertFeedbackChecked(capture(inserted)) } answers {
      stored.removeAll { old -> inserted.captured.any { it.FeedbackType == old.FeedbackType } }
      stored += inserted.captured
    }
    service = GorsePreferenceService(client, settings)
  }

  @Test
  fun `preference transitions are mutually exclusive and cancellable`() {
    assertThat(service.setPreference("user", "series", GorsePreference.LIKE)).isEqualTo(GorsePreference.LIKE)
    assertThat(service.getPreference("user", "series")).isEqualTo(GorsePreference.LIKE)

    assertThat(service.setPreference("user", "series", GorsePreference.DISLIKE)).isEqualTo(GorsePreference.DISLIKE)
    assertThat(stored.map { it.FeedbackType }).containsExactly("dislike")

    assertThat(service.setPreference("user", "series", GorsePreference.NONE)).isEqualTo(GorsePreference.NONE)
    assertThat(stored).isEmpty()
  }

  @Test
  fun `submitting the same preference repeatedly remains a single explicit state`() {
    service.setPreference("user", "series", GorsePreference.LIKE)
    service.setPreference("user", "series", GorsePreference.LIKE)

    assertThat(stored.map { it.FeedbackType }).containsExactly("like")
  }

  @Test
  fun `read feedback is retained through preference transitions`() {
    stored += feedback("read")

    service.setPreference("user", "series", GorsePreference.DISLIKE)
    service.setPreference("user", "series", GorsePreference.NONE)

    assertThat(stored.map { it.FeedbackType }).containsExactly("read")
  }

  @Test
  fun `invalid legacy feedback type configuration never deletes read`() {
    stored += feedback("read")
    every { settings.positiveFeedbackType } returns "read"

    assertThatThrownBy { service.setPreference("user", "series", GorsePreference.DISLIKE) }
      .isInstanceOf(ResponseStatusException::class.java)
      .hasMessageContaining("503 SERVICE_UNAVAILABLE")
    assertThat(stored.map { it.FeedbackType }).containsExactly("read")
    io.mockk.verify(exactly = 0) { client.deleteFeedbackChecked(any(), any(), any()) }
  }

  @Test
  fun `historical dual state resolves to dislike and is repaired on write`() {
    stored += listOf(feedback("like"), feedback("dislike"))

    assertThat(service.getPreference("user", "series")).isEqualTo(GorsePreference.DISLIKE)
    service.setPreference("user", "series", GorsePreference.LIKE)

    assertThat(stored.map { it.FeedbackType }).containsExactly("like")
  }

  @Test
  fun `failed transition restores original explicit feedback and returns bad gateway`() {
    stored += feedback("like")
    var failed = false
    every { client.insertFeedbackChecked(any()) } answers {
      val feedback = firstArg<List<GorseFeedback>>()
      if (!failed && feedback.any { it.FeedbackType == "dislike" }) {
        failed = true
        error("remote failure")
      }
      stored.removeAll { old -> feedback.any { it.FeedbackType == old.FeedbackType } }
      stored += feedback
    }

    assertThatThrownBy { service.setPreference("user", "series", GorsePreference.DISLIKE) }
      .isInstanceOf(ResponseStatusException::class.java)
      .hasMessageContaining("502 BAD_GATEWAY")
    assertThat(stored.map { it.FeedbackType }).containsExactly("like")
  }

  @Test
  fun `failed original read returns bad gateway without attempting a mutation`() {
    every { client.getFeedbackChecked("user", "series") } throws IllegalStateException("remote read failure")

    assertThatThrownBy { service.setPreference("user", "series", GorsePreference.DISLIKE) }
      .isInstanceOf(ResponseStatusException::class.java)
      .hasMessageContaining("502 BAD_GATEWAY")
    io.mockk.verify(exactly = 0) { client.deleteFeedbackChecked(any(), any(), any()) }
    io.mockk.verify(exactly = 0) { client.insertFeedbackChecked(any()) }
  }

  @Test
  fun `failed compensation is attached to the bad gateway cause`() {
    stored += feedback("like")
    every { client.insertFeedbackChecked(any()) } answers { error("remote write failure") }

    val failure = catchThrowable { service.setPreference("user", "series", GorsePreference.DISLIKE) }

    assertThat(failure).isInstanceOf(ResponseStatusException::class.java)
    assertThat((failure as ResponseStatusException).statusCode.value()).isEqualTo(502)
    assertThat(failure.cause?.suppressed).hasSize(1)
  }

  private fun feedback(type: String) = GorseFeedback(type, "user", "series", "2026-08-12T00:00:00Z")

  @Test
  fun `authenticated feedback verifies both signals before saving progress and cancellation retains read`() {
    val complete = mockk<() -> Unit>()
    every { complete.invoke() } answers {
      assertThat(stored.map { it.FeedbackType }).contains("read")
      assertThat(stored.count { it.FeedbackType in setOf("like", "dislike") }).isEqualTo(1)
    }

    service.setPreferenceAndComplete("user", "series", GorsePreference.LIKE, complete)
    service.setPreferenceAndComplete("user", "series", GorsePreference.DISLIKE, complete)
    service.setPreferenceAndComplete("user", "series", GorsePreference.DISLIKE, complete)
    service.setPreferenceAndComplete("user", "series", GorsePreference.NONE, complete)

    verify(exactly = 3) { complete.invoke() }
    assertThat(stored.map { it.FeedbackType }).containsExactly("read")
  }

  @Test
  fun `unconfirmed read feedback prevents local progress and restores preference`() {
    stored += feedback("like")
    val complete = mockk<() -> Unit>(relaxed = true)
    every { client.insertFeedbackChecked(any()) } answers {
      val incoming = firstArg<List<GorseFeedback>>().filter { it.FeedbackType != "read" }
      stored.removeAll { old -> incoming.any { it.FeedbackType == old.FeedbackType } }
      stored += incoming
    }

    assertThatThrownBy { service.setPreferenceAndComplete("user", "series", GorsePreference.DISLIKE, complete) }
      .isInstanceOf(ResponseStatusException::class.java)
    assertThat(stored).containsExactly(feedback("like"))
    verify(exactly = 0) { complete.invoke() }
  }

  @Test
  fun `database failure compensates new read and retry completes the original target`() {
    stored += feedback("like")
    assertThatThrownBy {
      service.setPreferenceAndComplete("user", "series", GorsePreference.DISLIKE) { error("database failure") }
    }.isInstanceOf(ResponseStatusException::class.java)
    assertThat(stored).containsExactly(feedback("like"))

    service.setPreferenceAndComplete("user", "series", GorsePreference.DISLIKE) {}
    assertThat(stored.map { it.FeedbackType }).containsExactlyInAnyOrder("dislike", "read")
  }

  @Test
  fun `failed completion preserves historical read and unrelated feedback`() {
    stored += listOf(feedback("read"), feedback("like"), feedback("bookmark"))
    assertThatThrownBy {
      service.setPreferenceAndComplete("user", "series", GorsePreference.DISLIKE) { error("database failure") }
    }.isInstanceOf(ResponseStatusException::class.java)
    assertThat(stored).containsExactlyInAnyOrder(feedback("read"), feedback("like"), feedback("bookmark"))
  }

  @Test
  fun `custom read feedback type is used`() {
    every { settings.feedbackType } returns "finished"
    service.setPreferenceAndComplete("user", "series", GorsePreference.LIKE) {}
    assertThat(stored.map { it.FeedbackType }).containsExactlyInAnyOrder("like", "finished")
  }

  @Test
  fun `failed explicit compensation still removes new read and allows retry`() {
    stored += feedback("like")
    var failRestoration = false
    every { client.insertFeedbackChecked(any()) } answers {
      val incoming = firstArg<List<GorseFeedback>>()
      if (failRestoration && incoming.any { it.FeedbackType == "like" }) error("compensation failure")
      stored.removeAll { old -> incoming.any { it.FeedbackType == old.FeedbackType } }
      stored += incoming
    }
    val failure =
      catchThrowable {
        service.setPreferenceAndComplete("user", "series", GorsePreference.DISLIKE) {
          failRestoration = true
          error("database failure")
        }
      }
    assertThat(failure.cause?.suppressed).hasSize(1)
    assertThat(stored).isEmpty()

    service.setPreferenceAndComplete("user", "series", GorsePreference.DISLIKE) {}
    assertThat(stored.map { it.FeedbackType }).containsExactlyInAnyOrder("dislike", "read")
  }

  @Test
  fun `another preference cannot overtake an in-flight completion for the same series`() {
    val executor = Executors.newFixedThreadPool(2)
    val completing = CountDownLatch(1)
    val release = CountDownLatch(1)
    try {
      val first =
        executor.submit<GorsePreference> {
          service.setPreferenceAndComplete("user", "series", GorsePreference.LIKE) {
            completing.countDown()
            check(release.await(5, TimeUnit.SECONDS))
          }
        }
      check(completing.await(5, TimeUnit.SECONDS))
      val second =
        executor.submit<GorsePreference> {
          service.setPreferenceAndComplete("user", "series", GorsePreference.DISLIKE) {}
        }
      assertThatThrownBy { second.get(100, TimeUnit.MILLISECONDS) }.isInstanceOf(TimeoutException::class.java)
      release.countDown()
      assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(GorsePreference.LIKE)
      assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(GorsePreference.DISLIKE)
      assertThat(stored.map { it.FeedbackType }).containsExactlyInAnyOrder("dislike", "read")
    } finally {
      release.countDown()
      executor.shutdownNow()
    }
  }
}
