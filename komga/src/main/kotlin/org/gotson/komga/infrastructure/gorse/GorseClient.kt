package org.gotson.komga.infrastructure.gorse

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient

private val logger = KotlinLogging.logger {}

@Component
class GorseClient(
  private val gorseSettings: GorseSettingsProvider,
) {
  private fun buildClient(): WebClient =
    WebClient.builder()
      .baseUrl(gorseSettings.apiUrl)
      .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      .defaultHeader("X-API-Key", gorseSettings.apiKey)
      .build()

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

  fun updateItem(itemId: String, item: GorseItem) {
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

  fun insertFeedback(feedback: List<GorseFeedback>) {
    if (feedback.isEmpty()) return
    try {
      logger.info { "Gorse: sending ${feedback.size} feedback entries: ${feedback.map { "${it.UserId}->${it.ItemId}(${it.FeedbackType})" }}" }
      val response = buildClient()
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
}
