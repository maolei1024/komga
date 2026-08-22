package org.gotson.komga.infrastructure.gorse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Component
class GorseClient(
  private val gorseSettings: GorseSettingsProvider,
) {
  private fun buildClient(
    apiUrl: String = gorseSettings.apiUrl,
    apiKey: String = gorseSettings.apiKey,
  ): WebClient =
    WebClient
      .builder()
      .baseUrl(apiUrl)
      .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      .defaultHeader("X-API-Key", apiKey)
      .build()

  fun testConnection(
    apiUrl: String,
    apiKey: String,
  ): GorseHealthStatus = testConnection(apiUrl, apiKey, CONNECTION_TEST_TIMEOUT)

  internal fun testConnection(
    apiUrl: String,
    apiKey: String,
    timeout: Duration,
  ): GorseHealthStatus {
    val client = buildClient(apiUrl, apiKey)
    val healthCheck =
      client
        .get()
        .uri("/api/health/ready")
        .retrieve()
        .bodyToMono(GorseHealthStatus::class.java)
        .switchIfEmpty(Mono.error(IllegalStateException("Gorse returned an empty health response")))
        .flatMap { health ->
          if (!health.Ready || !health.DataStoreConnected || !health.CacheStoreConnected) {
            Mono.just(health)
          } else {
            client
              .get()
              .uri("/api/item/{itemId}", CONNECTION_TEST_ITEM_ID)
              .exchangeToMono { response ->
                when {
                  response.statusCode().is2xxSuccessful || response.statusCode() == HttpStatus.NOT_FOUND -> response.releaseBody()
                  else -> response.createException().flatMap { Mono.error<Void>(it) }
                }
              }.thenReturn(health)
          }
        }

    return requireNotNull(healthCheck.block(timeout)) { "Gorse returned an empty connection test response" }
  }

  fun insertItem(item: GorseItem) {
    try {
      buildClient()
        .post()
        .uri("/api/item")
        .bodyValue(item)
        .retrieve()
        .bodyToMono(String::class.java)
        .block()
      logger.debug { "Gorse: inserted item ${item.ItemId}" }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to insert item ${item.ItemId}" }
    }
  }

  fun upsertItemChecked(item: GorseItem) {
    buildClient()
      .post()
      .uri("/api/item")
      .bodyValue(item)
      .retrieve()
      .bodyToMono(String::class.java)
      .block()
  }

  fun setHiddenChecked(
    itemId: String,
    hidden: Boolean,
  ) {
    buildClient()
      .patch()
      .uri("/api/item/$itemId")
      .bodyValue(mapOf("IsHidden" to hidden))
      .retrieve()
      .bodyToMono(String::class.java)
      .block()
  }

  fun getItemChecked(itemId: String): GorseItem =
    requireNotNull(
      buildClient()
        .get()
        .uri("/api/item/$itemId")
        .retrieve()
        .bodyToMono(GorseItem::class.java)
        .block(),
    ) { "Gorse returned an empty Item response for $itemId" }

  fun updateItem(
    itemId: String,
    item: GorseItem,
  ) {
    try {
      buildClient()
        .patch()
        .uri("/api/item/$itemId")
        .bodyValue(item)
        .retrieve()
        .bodyToMono(String::class.java)
        .block()
      logger.debug { "Gorse: updated item $itemId" }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to update item $itemId" }
    }
  }

  fun deleteItem(itemId: String) {
    try {
      buildClient()
        .delete()
        .uri("/api/item/$itemId")
        .retrieve()
        .bodyToMono(String::class.java)
        .block()
      logger.debug { "Gorse: deleted item $itemId" }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to delete item $itemId" }
    }
  }

  fun hideItem(itemId: String) {
    try {
      buildClient()
        .patch()
        .uri("/api/item/$itemId")
        .bodyValue(mapOf("IsHidden" to true))
        .retrieve()
        .bodyToMono(String::class.java)
        .block()
      logger.info { "Gorse: hidden item $itemId (preserved for training)" }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to hide item $itemId" }
    }
  }

  fun insertItems(items: List<GorseItem>) {
    if (items.isEmpty()) return
    try {
      buildClient()
        .post()
        .uri("/api/items")
        .bodyValue(items)
        .retrieve()
        .bodyToMono(String::class.java)
        .block()
      logger.debug { "Gorse: inserted ${items.size} items" }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to insert ${items.size} items" }
    }
  }

  fun insertUser(user: GorseUser) {
    try {
      buildClient()
        .post()
        .uri("/api/user")
        .bodyValue(user)
        .retrieve()
        .bodyToMono(String::class.java)
        .block()
      logger.debug { "Gorse: inserted user ${user.UserId}" }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to insert user ${user.UserId}" }
    }
  }

  fun insertUsers(users: List<GorseUser>) {
    if (users.isEmpty()) return
    try {
      buildClient()
        .post()
        .uri("/api/users")
        .bodyValue(users)
        .retrieve()
        .bodyToMono(String::class.java)
        .block()
      logger.debug { "Gorse: inserted ${users.size} users" }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to insert ${users.size} users" }
    }
  }

  fun getRecommendations(
    userId: String,
    n: Int = 20,
    offset: Int = 0,
  ): List<GorseRecommendation> =
    try {
      val response =
        buildClient()
          .get()
          .uri("/api/recommend/$userId?n=$n&offset=$offset")
          .header("X-API-Version", "2")
          .retrieve()
          .bodyToMono(object : org.springframework.core.ParameterizedTypeReference<List<GorseRecommendation>>() {})
          .block() ?: emptyList()
      logger.debug { "Gorse: got ${response.size} recommendations for user $userId" }
      response
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to get recommendations for user $userId" }
      emptyList()
    }

  fun insertFeedback(feedback: List<GorseFeedback>) {
    if (feedback.isEmpty()) return
    try {
      logger.info { "Gorse: sending ${feedback.size} feedback entries: ${feedback.map { "${it.UserId}->${it.ItemId}(${it.FeedbackType})" }}" }
      val response =
        buildClient()
          .put()
          .uri("/api/feedback")
          .bodyValue(feedback)
          .retrieve()
          .bodyToMono(String::class.java)
          .block()
      logger.info { "Gorse: inserted ${feedback.size} feedback entries, response: $response" }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to insert ${feedback.size} feedback entries" }
    }
  }

  fun insertFeedbackChecked(feedback: List<GorseFeedback>) {
    if (feedback.isEmpty()) return
    buildClient()
      .put()
      .uri("/api/feedback")
      .bodyValue(feedback)
      .retrieve()
      .bodyToMono(String::class.java)
      .block()
  }

  fun getFeedbackChecked(
    userId: String,
    itemId: String,
  ): List<GorseFeedback> =
    buildClient()
      .get()
      .uri("/api/feedback/$userId/$itemId")
      .retrieve()
      .bodyToMono(object : org.springframework.core.ParameterizedTypeReference<List<GorseFeedback>>() {})
      .block() ?: emptyList()

  fun getUserFeedbackByTypeChecked(
    userId: String,
    feedbackType: String,
  ): List<GorseFeedback> =
    buildClient()
      .get()
      .uri("/api/user/$userId/feedback/$feedbackType")
      .retrieve()
      .bodyToMono(object : org.springframework.core.ParameterizedTypeReference<List<GorseFeedback>>() {})
      .block() ?: emptyList()

  fun getUserFeedbackByType(
    userId: String,
    feedbackType: String,
  ): List<GorseFeedback> =
    try {
      val response =
        buildClient()
          .get()
          .uri("/api/user/$userId/feedback/$feedbackType")
          .retrieve()
          .bodyToMono(object : org.springframework.core.ParameterizedTypeReference<List<GorseFeedback>>() {})
          .block() ?: emptyList()
      logger.debug { "Gorse: got ${response.size} $feedbackType feedbacks for user $userId" }
      response
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to get $feedbackType feedbacks for user $userId" }
      emptyList()
    }

  fun deleteFeedback(
    feedbackType: String,
    userId: String,
    itemId: String,
  ) {
    try {
      buildClient()
        .delete()
        .uri("/api/feedback/$feedbackType/$userId/$itemId")
        .retrieve()
        .bodyToMono(String::class.java)
        .block()
      logger.debug { "Gorse: deleted $feedbackType feedback for user $userId item $itemId" }
    } catch (e: Exception) {
      logger.error(e) { "Gorse: failed to delete $feedbackType feedback for user $userId item $itemId" }
    }
  }

  fun deleteFeedbackChecked(
    feedbackType: String,
    userId: String,
    itemId: String,
  ) {
    buildClient()
      .delete()
      .uri("/api/feedback/$feedbackType/$userId/$itemId")
      .retrieve()
      .bodyToMono(String::class.java)
      .block()
  }

  companion object {
    private val CONNECTION_TEST_TIMEOUT: Duration = Duration.ofSeconds(5)
    private const val CONNECTION_TEST_ITEM_ID = "__komga_connection_test__"
  }
}
