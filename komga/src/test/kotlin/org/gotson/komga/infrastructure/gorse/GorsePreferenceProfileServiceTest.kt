package org.gotson.komga.infrastructure.gorse

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.MetadataEnrichmentBucket
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSettingsProvider
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GorsePreferenceProfileServiceTest {
  private val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
  private val database = DSL.using(connection, SQLDialect.SQLITE)
  private val client = mockk<GorseClient>()
  private val gorse = mockk<GorseSettingsProvider>()
  private val settings = mockk<MetadataEnrichmentSettingsProvider>()
  private val labelBuilder = mockk<GorseLabelBuilder>()
  private val feedback = ConcurrentHashMap<Triple<String, String, String>, GorseFeedback>()
  private val items = ConcurrentHashMap<String, GorseItem>()
  private val service = GorsePreferenceProfileService(database, jacksonObjectMapper(), client, gorse, settings, labelBuilder)
  private val profile = GorsePreferenceProfile(positive = PreferenceDistribution(20, PreferenceAxis(5.0, .3), PreferenceAxis(2.0, .3)))

  @BeforeEach
  fun setup() {
    database.execute(
      """CREATE TABLE GORSE_PREFERENCE_PROFILE
      (USER_ID text PRIMARY KEY, PROFILE_JSON text, BATCH_ID text, STATUS text DEFAULT 'DRAFT',
      ITEM_IDS_JSON text DEFAULT '[]', LAST_ERROR text, UPDATED_DATE text, APPLIED_PROFILE_JSON text, PREVIOUS_PROFILE_JSON text,
      PROCESSED integer DEFAULT 0, TOTAL integer DEFAULT 0)""",
    )
    every { gorse.enabled } returns true
    every { gorse.positiveFeedbackType } returns "favorite"
    every { gorse.negativeFeedbackType } returns "dislike"
    every { settings.pageSizeBuckets } returns listOf(MetadataEnrichmentBucket(1, 10, "pageSize_1-10"), MetadataEnrichmentBucket(11, null, "pageSize_11+"))
    every { settings.tagSizeBuckets } returns listOf(MetadataEnrichmentBucket(0, 5, "tagSize_0-5"), MetadataEnrichmentBucket(6, null, "tagSize_6+"))
    every { client.getUserFeedbackChecked(any()) } answers { feedback.values.filter { it.UserId == firstArg<String>() } }
    every { client.deleteFeedbackChecked(any(), any(), any()) } answers {
      feedback.remove(Triple(firstArg(), secondArg(), thirdArg()))
      Unit
    }
    every { client.deleteItemChecked(any()) } answers {
      items.remove(firstArg<String>())
      Unit
    }
    every { client.insertItemsChecked(any()) } answers {
      firstArg<List<GorseItem>>().forEach { items[it.ItemId] = it }
    }
    every { client.insertFeedbackChecked(any()) } answers {
      firstArg<List<GorseFeedback>>().forEach { feedback[Triple(it.FeedbackType, it.UserId, it.ItemId)] = it }
    }
  }

  @AfterEach
  fun close() {
    service.close()
    connection.close()
  }

  @Test
  fun `same count replacement removes the previous batch and preserves real feedback and other users`() {
    val real = GorseFeedback("favorite", "user", "real-series", "2026-11-11T00:00:00Z")
    val other = GorseFeedback("favorite", "other", "synthetic:komga:other:batch:0", real.Timestamp)
    feedback[Triple(real.FeedbackType, real.UserId, real.ItemId)] = real
    feedback[Triple(other.FeedbackType, other.UserId, other.ItemId)] = other
    service.save("user", profile)
    service.apply("user")
    delivered()
    val first = items.keys.toSet()
    assertThat(first).hasSize(20)
    assertThat(feedback.values.filter { it.ItemId in first }).allMatch { java.time.Instant.parse(it.Timestamp) < java.time.Instant.parse(real.Timestamp) }
    assertThat(items.values).allMatch { it.IsHidden && it.Labels["tags"] == emptyList<String>() }
    service.apply("user")
    delivered()
    assertThat(items.keys).hasSize(20).doesNotContainAnyElementsOf(first)
    assertThat(feedback.values).contains(real, other).hasSize(22)
    service.apply("user", true)
    delivered()
    assertThat(items).isEmpty()
    assertThat(feedback.values).containsExactlyInAnyOrder(real, other)
  }

  @Test
  fun `partial insertion failure is cleaned and can be retried`() {
    every { client.insertFeedbackChecked(any()) } answers {
      val first = firstArg<List<GorseFeedback>>().first()
      feedback[Triple(first.FeedbackType, first.UserId, first.ItemId)] = first
      throw IllegalStateException("failed")
    }
    service.save("user", profile)
    service.apply("user")
    await("FAILED")
    assertThat(items).isEmpty()
    assertThat(feedback).isEmpty()
    every { client.insertFeedbackChecked(any()) } answers {
      firstArg<List<GorseFeedback>>().forEach { feedback[Triple(it.FeedbackType, it.UserId, it.ItemId)] = it }
    }
    service.apply("user")
    delivered()
    assertThat(items).hasSize(20)
    assertThat(feedback).hasSize(20)
  }

  @Test
  fun `five thousand managed samples are fully replaced and report progress`() {
    service.save("user", profile.copy(positive = profile.positive.copy(count = 5000)))
    service.apply("user")
    delivered()
    assertThat(items).hasSize(5000)
    assertThat(service.get("user").processed).isEqualTo(10000)
    assertThat(service.get("user").total).isEqualTo(10000)
    service.save("user", profile)
    service.apply("user")
    delivered()
    assertThat(items).hasSize(20)
    assertThat(feedback).hasSize(20)
  }

  @Test
  fun `duplicate apply is rejected while cleanup is running`() {
    val started = java.util.concurrent.CountDownLatch(1)
    val release = java.util.concurrent.CountDownLatch(1)
    every { client.getUserFeedbackChecked("user") } answers {
      started.countDown()
      check(release.await(5, TimeUnit.SECONDS))
      emptyList()
    }
    service.save("user", profile.copy(positive = profile.positive.copy(count = 0)))
    service.apply("user")
    check(started.await(5, TimeUnit.SECONDS))
    try {
      org.assertj.core.api.Assertions
        .assertThatThrownBy { service.apply("user") }
        .isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
    } finally {
      release.countDown()
    }
    delivered()
  }

  @Test
  fun `conflicting signs and more than five thousand samples are rejected before writes`() {
    assertThat(service.preview(profile.copy(negative = profile.positive)).conflicts).isNotEmpty()
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException().isThrownBy {
      service.preview(profile.copy(positive = profile.positive.copy(count = 5000), negative = profile.positive))
    }
  }

  @Test
  fun `startup recovery removes an interrupted batch before restoring its samples`() {
    service.save("user", profile)
    service.apply("user")
    delivered()
    val incomplete = items.keys.take(10).toSet()
    items.keys.removeIf { it !in incomplete }
    feedback.keys.removeIf { it.third !in incomplete }
    database.execute("UPDATE GORSE_PREFERENCE_PROFILE SET STATUS = 'INSERTING' WHERE USER_ID = 'user'")
    service.resume()
    delivered()
    assertThat(items).hasSize(20)
    assertThat(feedback).hasSize(20)
  }

  private fun delivered() = await("DELIVERED")

  private fun await(status: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (service.get("user").status != status && System.nanoTime() < deadline) Thread.sleep(10)
    assertThat(service.get("user").status).isEqualTo(status)
  }
}
