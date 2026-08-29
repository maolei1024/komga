package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.databind.JsonNode
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.net.URI
import java.time.Duration

private val logger = KotlinLogging.logger {}

@Service
class MetadataEnrichmentAiClient(
  private val settings: MetadataEnrichmentSettingsProvider,
  private val webClientBuilder: WebClient.Builder,
) {
  fun translate(summary: String): String? {
    if (summary.isBlank()) return null
    check(settings.aiEnabled && settings.aiConfigured) { "AI title processing is not configured and enabled" }

    val client =
      webClientBuilder
        .clone()
        .baseUrl(normalizeBaseUrl(settings.aiBaseUrl))
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${settings.aiApiKey}")
        .build()
    var lastError: Throwable? = null
    repeat(settings.aiMaxRetries + 1) { attempt ->
      try {
        val response =
          client
            .post()
            .uri("chat/completions")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
              mapOf(
                "model" to settings.aiModel,
                "messages" to
                  listOf(
                    mapOf("role" to "system", "content" to settings.aiPrompt),
                    mapOf("role" to "user", "content" to "待翻译文本:$summary"),
                  ),
                "temperature" to 0.2,
                "top_p" to 0.7,
                "max_tokens" to 256,
                "stream" to false,
              ),
            ).retrieve()
            .bodyToMono(JsonNode::class.java)
            .block(Duration.ofSeconds(settings.aiTimeoutSeconds.toLong()))
        val content =
          response
            ?.path("choices")
            ?.path(0)
            ?.path("message")
            ?.path("content")
            ?.asText()
            .orEmpty()
        return parseTitle(content) ?: error("AI response did not contain a valid single-line title")
      } catch (e: Exception) {
        lastError = e
        logger.warn { "AI title request attempt ${attempt + 1}/${settings.aiMaxRetries + 1} failed: ${sanitize(e.message.orEmpty())}" }
        if (attempt < settings.aiMaxRetries) Thread.sleep(minOf(1L shl attempt, 10L) * 1000L)
      }
    }
    throw IllegalStateException("AI title translation failed: ${sanitize(lastError?.message.orEmpty())}", lastError)
  }

  internal fun parseTitle(content: String): String? {
    var value = content.trim()
    val marked = Regex("AAAA(.*?)BBBB", RegexOption.DOT_MATCHES_ALL).findAll(value).map { it.groupValues[1].trim() }.maxByOrNull { it.length }
    if (marked != null) return marked.takeIf { it.isNotBlank() && it.length <= 255 }
    if (value.contains("AAAA") || value.contains("BBBB")) return null
    if (value.startsWith("```") && value.endsWith("```"))
      value =
        value
          .lines()
          .drop(1)
          .dropLast(1)
          .joinToString("\n")
          .trim()
    val lines = value.lines().map { it.trim() }.filter { it.isNotBlank() }
    if (lines.size != 1) return null
    var title = lines.single()
    val quotes = setOf('"' to '"', '\'' to '\'', '“' to '”', '「' to '」', '『' to '』')
    if (title.length >= 2 && (title.first() to title.last()) in quotes) title = title.substring(1, title.length - 1).trim()
    if (title.isBlank() || title.length > 255 || REFUSAL.containsMatchIn(title)) return null
    if (title.startsWith("翻译结果") || title.startsWith("译名") || title.startsWith("标题翻译")) return null
    return title
  }

  private fun normalizeBaseUrl(value: String): String {
    val trimmed = value.trim().trimEnd('/')
    val uri = URI(trimmed)
    require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) { "AI base URL must be a valid HTTP(S) URL" }
    val apiRoot = if (uri.path.isNullOrBlank() || uri.path == "/") "$trimmed/v1" else trimmed
    return "$apiRoot/"
  }

  private fun sanitize(message: String): String {
    val withoutKey = if (settings.aiApiKey.isBlank()) message else message.replace(settings.aiApiKey, "[REDACTED]")
    return AUTHORIZATION.replace(withoutKey, "${'$'}1[REDACTED]")
  }

  companion object {
    private val REFUSAL = Regex("^(抱歉|对不起|无法|不能|我不能|i\\s+(?:am\\s+sorry|cannot|can't)|sorry\\b)", RegexOption.IGNORE_CASE)
    private val AUTHORIZATION = Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer|key)?\\s*\\S+")
  }
}
