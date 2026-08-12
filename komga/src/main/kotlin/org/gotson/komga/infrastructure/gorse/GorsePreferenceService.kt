package org.gotson.komga.infrastructure.gorse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.gotson.komga.interfaces.api.rest.dto.GorsePreference
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

@Service
class GorsePreferenceService(
  private val gorseClient: GorseClient,
  private val gorseSettings: GorseSettingsProvider,
) {
  private val locks = Array(LOCK_SEGMENTS) { ReentrantLock() }

  fun getPreference(
    userId: String,
    seriesId: String,
  ): GorsePreference {
    val types = feedbackTypes()
    return try {
      resolve(gorseClient.getFeedbackChecked(userId, seriesId), types)
    } catch (e: Exception) {
      throw badGateway("Failed to read Gorse preference for user $userId and series $seriesId", e)
    }
  }

  fun getDislikedSeriesIds(userId: String): Set<String> {
    val types = feedbackTypes()
    return gorseClient
      .getUserFeedbackByTypeChecked(userId, types.negative)
      .mapTo(mutableSetOf()) { it.ItemId }
  }

  fun setPreference(
    userId: String,
    seriesId: String,
    preference: GorsePreference,
  ): GorsePreference =
    lockFor(userId, seriesId).withLock {
      val types = feedbackTypes()
      val original =
        try {
          readExplicitFeedback(userId, seriesId, types)
        } catch (e: Exception) {
          throw badGateway("Failed to read original Gorse preference for user $userId and series $seriesId", e)
        }
      try {
        apply(userId, seriesId, preference, types)
        val verified = readExplicitFeedback(userId, seriesId, types)
        check(matches(verified, preference, types)) {
          "Gorse preference verification failed: expected $preference, found ${resolve(verified, types)}"
        }
        preference
      } catch (primary: Exception) {
        val compensation = runCatching { restore(userId, seriesId, original, types) }.exceptionOrNull()
        if (compensation != null) {
          logger.error(compensation) {
            "Gorse preference compensation failed for user $userId and series $seriesId after: ${primary.message}"
          }
          primary.addSuppressed(compensation)
        } else {
          logger.warn(primary) { "Gorse preference update failed and original state was restored for user $userId and series $seriesId" }
        }
        throw badGateway("Failed to update Gorse preference for user $userId and series $seriesId", primary)
      }
    }

  private fun readExplicitFeedback(
    userId: String,
    seriesId: String,
    types: FeedbackTypes,
  ): List<GorseFeedback> {
    val explicitTypes = setOf(types.positive, types.negative)
    return gorseClient.getFeedbackChecked(userId, seriesId).filter { it.FeedbackType in explicitTypes }
  }

  private fun apply(
    userId: String,
    seriesId: String,
    preference: GorsePreference,
    types: FeedbackTypes,
  ) {
    deleteExplicit(userId, seriesId, types)
    val feedbackType =
      when (preference) {
        GorsePreference.NONE -> null
        GorsePreference.LIKE -> types.positive
        GorsePreference.DISLIKE -> types.negative
      }
    feedbackType?.let {
      gorseClient.insertFeedbackChecked(listOf(feedback(it, userId, seriesId)))
    }
  }

  private fun restore(
    userId: String,
    seriesId: String,
    original: List<GorseFeedback>,
    types: FeedbackTypes,
  ) {
    deleteExplicit(userId, seriesId, types)
    if (original.isNotEmpty()) gorseClient.insertFeedbackChecked(original)
    val restored = readExplicitFeedback(userId, seriesId, types)
    check(restored.map { it.FeedbackType }.toSet() == original.map { it.FeedbackType }.toSet()) {
      "Gorse preference compensation verification failed"
    }
  }

  private fun deleteExplicit(
    userId: String,
    seriesId: String,
    types: FeedbackTypes,
  ) {
    gorseClient.deleteFeedbackChecked(types.positive, userId, seriesId)
    gorseClient.deleteFeedbackChecked(types.negative, userId, seriesId)
  }

  private fun resolve(
    feedback: List<GorseFeedback>,
    types: FeedbackTypes,
  ): GorsePreference =
    when {
      feedback.any { it.FeedbackType == types.negative } -> GorsePreference.DISLIKE
      feedback.any { it.FeedbackType == types.positive } -> GorsePreference.LIKE
      else -> GorsePreference.NONE
    }

  private fun matches(
    feedback: List<GorseFeedback>,
    preference: GorsePreference,
    types: FeedbackTypes,
  ): Boolean {
    val actualTypes = feedback.map { it.FeedbackType }.toSet()
    return when (preference) {
      GorsePreference.NONE -> actualTypes.isEmpty()
      GorsePreference.LIKE -> actualTypes == setOf(types.positive)
      GorsePreference.DISLIKE -> actualTypes == setOf(types.negative)
    }
  }

  private fun feedbackTypes(): FeedbackTypes {
    val types =
      FeedbackTypes(
        read = gorseSettings.feedbackType.trim(),
        positive = gorseSettings.positiveFeedbackType.trim(),
        negative = gorseSettings.negativeFeedbackType.trim(),
      )
    val values = listOf(types.read, types.positive, types.negative)
    if (values.any { it.isBlank() } || values.distinct().size != values.size) {
      throw ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Gorse read, positive, and negative feedback types must be non-blank and pairwise distinct",
      )
    }
    return types
  }

  private fun feedback(
    feedbackType: String,
    userId: String,
    seriesId: String,
  ) = GorseFeedback(
    FeedbackType = feedbackType,
    UserId = userId,
    ItemId = seriesId,
    Timestamp = ZonedDateTime.now(ZoneOffset.UTC).format(ISO_UTC_FORMATTER),
  )

  private fun lockFor(
    userId: String,
    seriesId: String,
  ): ReentrantLock = locks[(31 * userId.hashCode() + seriesId.hashCode()).and(Int.MAX_VALUE) % locks.size]

  private fun badGateway(
    message: String,
    cause: Exception,
  ): ResponseStatusException {
    logger.error(cause) { message }
    return ResponseStatusException(HttpStatus.BAD_GATEWAY, message, cause)
  }

  companion object {
    private const val LOCK_SEGMENTS = 256
    private val ISO_UTC_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
  }

  private data class FeedbackTypes(
    val read: String,
    val positive: String,
    val negative: String,
  )
}
