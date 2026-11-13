package org.gotson.komga.infrastructure.gorse

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.gotson.komga.domain.model.KomgaUser
import org.gotson.komga.infrastructure.metadata.enrichment.MetadataEnrichmentSettingsProvider
import org.jooq.DSLContext
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors

private val preferenceLogger = KotlinLogging.logger {}

@Service
class GorsePreferenceProfileService(
  private val database: DSLContext,
  private val mapper: ObjectMapper,
  private val client: GorseClient,
  private val gorse: GorseSettingsProvider,
  private val settings: MetadataEnrichmentSettingsProvider,
  private val labelBuilder: GorseLabelBuilder,
) {
  private val worker = Executors.newSingleThreadExecutor()
  private val mutationLock = Any()

  fun get(userId: String): PreferenceProfileStatus {
    val row =
      database.fetchOne("SELECT * FROM GORSE_PREFERENCE_PROFILE WHERE USER_ID = ?", userId)
        ?: return PreferenceProfileStatus(GorsePreferenceProfile(), "DRAFT")
    return PreferenceProfileStatus(
      profile = mapper.readValue(row.get("PROFILE_JSON", String::class.java), GorsePreferenceProfile::class.java),
      status = row.get("STATUS", String::class.java)!!,
      batchId = row.get("BATCH_ID", String::class.java),
      delivered = if (row.get("STATUS") == "DELIVERED") ids(userId).size else 0,
      processed = row.get("PROCESSED", Int::class.java) ?: 0,
      total = row.get("TOTAL", Int::class.java) ?: 0,
      lastError = row.get("LAST_ERROR", String::class.java),
      previousProfile = row.get("PREVIOUS_PROFILE_JSON", String::class.java)?.let { mapper.readValue(it, GorsePreferenceProfile::class.java) },
    )
  }

  fun preview(
    profile: GorsePreferenceProfile,
    user: KomgaUser? = null,
  ): PreferencePreview {
    require(profile.positive.count.toLong() + profile.negative.count <= 5000) { "Total samples cannot exceed 5000" }
    val positive = GorsePreferenceAllocator.allocate(profile.positive, settings.pageSizeBuckets, settings.tagSizeBuckets)
    val negative = GorsePreferenceAllocator.allocate(profile.negative, settings.pageSizeBuckets, settings.tagSizeBuckets)
    val coverage = user?.let(labelBuilder::coverage).orEmpty()
    val pairs = settings.pageSizeBuckets.flatMap { page -> settings.tagSizeBuckets.map { tag -> page.label.lowercase() to tag.label.lowercase() } }
    val cells = pairs.mapIndexed { index, pair -> PreferenceCell(pair.first, pair.second, positive[index], negative[index], coverage[pair] ?: 0) }
    return PreferencePreview(cells, cells.filter { it.positive > 0 && it.negative > 0 }.map { "${it.pageBucket}/${it.tagBucket}" }, positive.sum() + negative.sum())
  }

  fun save(
    userId: String,
    profile: GorsePreferenceProfile,
  ): PreferenceProfileStatus =
    synchronized(mutationLock) {
      ensureIdle(userId)
      preview(profile)
      database.execute(
        "INSERT INTO GORSE_PREFERENCE_PROFILE (USER_ID, PROFILE_JSON) VALUES (?, ?) ON CONFLICT (USER_ID) DO UPDATE SET PROFILE_JSON = excluded.PROFILE_JSON, STATUS = 'DRAFT', LAST_ERROR = NULL, UPDATED_DATE = CURRENT_TIMESTAMP",
        userId,
        mapper.writeValueAsString(profile),
      )
      get(userId)
    }

  fun apply(
    userId: String,
    withdraw: Boolean = false,
  ): PreferenceProfileStatus =
    synchronized(mutationLock) {
      ensureIdle(userId)
      require(gorse.enabled) { "Gorse is disabled" }
      val profile = if (withdraw) GorsePreferenceProfile() else get(userId).profile
      val preview = preview(profile)
      require(preview.conflicts.isEmpty()) { "Positive and negative samples overlap in the same bucket" }
      val batch = UUID.randomUUID().toString()
      database.execute(
        "INSERT INTO GORSE_PREFERENCE_PROFILE (USER_ID, PROFILE_JSON, BATCH_ID, STATUS) VALUES (?, ?, ?, 'PENDING') ON CONFLICT (USER_ID) DO UPDATE SET PROFILE_JSON = excluded.PROFILE_JSON, BATCH_ID = excluded.BATCH_ID, STATUS = 'PENDING', PROCESSED = 0, TOTAL = 0, LAST_ERROR = NULL, UPDATED_DATE = CURRENT_TIMESTAMP",
        userId,
        mapper.writeValueAsString(profile),
        batch,
      )
      worker.submit { deliver(userId) }
      get(userId)
    }

  @EventListener(ApplicationReadyEvent::class)
  fun resume() {
    database.fetch("SELECT USER_ID FROM GORSE_PREFERENCE_PROFILE WHERE STATUS IN ('PENDING', 'CLEANING', 'INSERTING')").forEach { row ->
      worker.submit { deliver(row.get("USER_ID", String::class.java)!!) }
    }
  }

  private fun deliver(userId: String) {
    try {
      val saved = get(userId)
      val batch = requireNotNull(saved.batchId)
      val preview = preview(saved.profile)
      require(preview.conflicts.isEmpty()) { "Positive and negative samples overlap" }
      status(userId, "CLEANING")
      val allFeedback = client.getUserFeedbackChecked(userId)
      val feedback = allFeedback.filter { owned(userId, it.ItemId) }
      val oldIds = ids(userId) + feedback.map { it.ItemId }
      feedback.forEach { client.deleteFeedbackChecked(it.FeedbackType, userId, it.ItemId) }
      oldIds.distinct().forEach { itemId ->
        require(owned(userId, itemId)) { "Refusing to delete an unmanaged item" }
        client.deleteItemChecked(itemId)
      }
      check(client.getUserFeedbackChecked(userId).none { owned(userId, it.ItemId) }) { "Previous synthetic feedback was not removed" }
      val items = mutableListOf<GorseItem>()
      val nextFeedback = mutableListOf<GorseFeedback>()
      val timestamp =
        allFeedback
          .filterNot { owned(userId, it.ItemId) }
          .mapNotNull { runCatching { Instant.parse(it.Timestamp) }.getOrNull() }
          .minOrNull()
          ?.minusSeconds(1)
          ?.toString() ?: Instant.EPOCH.toString()
      preview.cells.forEach { cell ->
        listOf(cell.positive to gorse.positiveFeedbackType, cell.negative to gorse.negativeFeedbackType).forEach { (count, type) ->
          repeat(count) {
            val id = "synthetic:komga:$userId:$batch:${items.size}"
            items +=
              GorseItem(
                id,
                true,
                mapOf(
                  "preference_buckets" to listOf(cell.pageBucket, cell.tagBucket),
                ),
                Timestamp = timestamp,
              )
            nextFeedback += GorseFeedback(type, userId, id, timestamp)
          }
        }
      }
      database.execute("UPDATE GORSE_PREFERENCE_PROFILE SET ITEM_IDS_JSON = ?, STATUS = 'INSERTING', PROCESSED = 0, TOTAL = ? WHERE USER_ID = ?", mapper.writeValueAsString(items.map { it.ItemId }), items.size * 2, userId)
      items.chunked(100).forEach { chunk ->
        client.insertItemsChecked(chunk)
        progress(userId, chunk.size)
      }
      nextFeedback.chunked(100).forEach { chunk ->
        client.insertFeedbackChecked(chunk)
        progress(userId, chunk.size)
      }
      val actual = client.getUserFeedbackChecked(userId).filter { owned(userId, it.ItemId) }
      check(actual.map { it.FeedbackType to it.ItemId }.toSet() == nextFeedback.map { it.FeedbackType to it.ItemId }.toSet()) { "Synthetic feedback verification failed" }
      database.execute(
        "UPDATE GORSE_PREFERENCE_PROFILE SET STATUS = 'DELIVERED', PREVIOUS_PROFILE_JSON = APPLIED_PROFILE_JSON, APPLIED_PROFILE_JSON = PROFILE_JSON, LAST_ERROR = NULL, UPDATED_DATE = CURRENT_TIMESTAMP WHERE USER_ID = ?",
        userId,
      )
    } catch (exception: Exception) {
      preferenceLogger.error(exception) { "Gorse preference delivery failed for user $userId" }
      val cleaned =
        runCatching {
          client
            .getUserFeedbackChecked(userId)
            .filter { owned(userId, it.ItemId) }
            .forEach { client.deleteFeedbackChecked(it.FeedbackType, userId, it.ItemId) }
          ids(userId).filter { owned(userId, it) }.forEach(client::deleteItemChecked)
        }.isSuccess
      database.execute(
        "UPDATE GORSE_PREFERENCE_PROFILE SET STATUS = 'FAILED', LAST_ERROR = ?, UPDATED_DATE = CURRENT_TIMESTAMP WHERE USER_ID = ?",
        exception.javaClass.simpleName + if (cleaned) " (partial samples removed)" else " (cleanup pending; retry required)",
        userId,
      )
    }
  }

  private fun ensureIdle(userId: String) {
    if (get(userId).status in setOf("PENDING", "CLEANING", "INSERTING")) throw ResponseStatusException(HttpStatus.CONFLICT, "A preference update is already running")
  }

  private fun status(
    userId: String,
    value: String,
  ) {
    database.execute("UPDATE GORSE_PREFERENCE_PROFILE SET STATUS = ?, UPDATED_DATE = CURRENT_TIMESTAMP WHERE USER_ID = ?", value, userId)
  }

  private fun progress(
    userId: String,
    amount: Int,
  ) {
    database.execute("UPDATE GORSE_PREFERENCE_PROFILE SET PROCESSED = PROCESSED + ?, UPDATED_DATE = CURRENT_TIMESTAMP WHERE USER_ID = ?", amount, userId)
  }

  private fun ids(userId: String): List<String> =
    database
      .fetchOne("SELECT ITEM_IDS_JSON FROM GORSE_PREFERENCE_PROFILE WHERE USER_ID = ?", userId)
      ?.get(0, String::class.java)
      ?.let { mapper.readValue(it, object : TypeReference<List<String>>() {}) } ?: emptyList()

  private fun owned(
    userId: String,
    itemId: String,
  ): Boolean = itemId.startsWith("synthetic:komga:$userId:")

  @PreDestroy
  fun close() {
    worker.shutdownNow()
  }
}
