package org.gotson.komga.infrastructure.gorse

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class GorseClientTest {
  private lateinit var server: HttpServer
  private lateinit var client: GorseClient
  private val apiVersion = AtomicReference<String>()
  private val query = AtomicReference<String>()
  private var responseCode = 200
  private var responseBody = RECOMMENDATIONS_RESPONSE
  private val feedbackMethod = AtomicReference<String>()
  private val feedbackPath = AtomicReference<String>()
  private var healthResponseCode = 200
  private var healthResponseBody = READY_RESPONSE
  private var probeResponseCode = 404
  private var hangHealthResponse = false
  private val probeApiKey = AtomicReference<String>()
  private val connectionTestRequests = CopyOnWriteArrayList<String>()

  @BeforeEach
  fun setup() {
    responseCode = 200
    responseBody = RECOMMENDATIONS_RESPONSE
    healthResponseCode = 200
    healthResponseBody = READY_RESPONSE
    probeResponseCode = 404
    hangHealthResponse = false
    probeApiKey.set(null)
    connectionTestRequests.clear()
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/api/recommend/user") { exchange ->
      apiVersion.set(exchange.requestHeaders.getFirst("X-API-Version"))
      query.set(exchange.requestURI.query)
      writeResponse(exchange, responseCode, responseBody)
    }
    server.createContext("/api/feedback") { exchange ->
      feedbackMethod.set(exchange.requestMethod)
      feedbackPath.set(exchange.requestURI.path)
      val body =
        if (exchange.requestMethod == "GET") {
          """[{"FeedbackType":"dislike","UserId":"user","ItemId":"item","Timestamp":"2026-08-12T00:00:00Z"}]"""
        } else {
          "{}"
        }
      writeResponse(exchange, responseCode, body)
    }
    server.createContext("/api/health/ready") { exchange ->
      connectionTestRequests += "${exchange.requestMethod} ${exchange.requestURI.path}"
      if (!hangHealthResponse) writeResponse(exchange, healthResponseCode, healthResponseBody)
    }
    server.createContext("/api/item/__komga_connection_test__") { exchange ->
      connectionTestRequests += "${exchange.requestMethod} ${exchange.requestURI.path}"
      probeApiKey.set(exchange.requestHeaders.getFirst("X-API-Key"))
      writeResponse(exchange, probeResponseCode, if (probeResponseCode >= 400) "probe response" else "{}")
    }
    server.start()

    val settings = mockk<GorseSettingsProvider>()
    every { settings.apiUrl } returns baseUrl()
    every { settings.apiKey } returns "test-key"
    client = GorseClient(settings)
  }

  @AfterEach
  fun tearDown() {
    server.stop(0)
  }

  @Test
  fun `given Gorse API v2 when retrieving recommendations then scores are returned`() {
    val result = client.getRecommendations("user", n = 100, offset = 0)

    assertThat(result)
      .containsExactly(
        GorseRecommendation(Id = "first", Score = 0.9),
        GorseRecommendation(Id = "second", Score = 0.5),
      )
    assertThat(apiVersion.get()).isEqualTo("2")
    assertThat(query.get()).isEqualTo("n=100&offset=0")
  }

  @Test
  fun `given Gorse failure when retrieving recommendations then empty list is returned`() {
    responseCode = 500
    responseBody = "server error"

    assertThat(client.getRecommendations("user", n = 100, offset = 0)).isEmpty()
  }

  @Test
  fun `checked feedback operations use pair endpoint and propagate remote failures`() {
    assertThat(client.getFeedbackChecked("user", "item").map { it.FeedbackType }).containsExactly("dislike")
    assertThat(feedbackMethod.get()).isEqualTo("GET")
    assertThat(feedbackPath.get()).isEqualTo("/api/feedback/user/item")

    responseCode = 500
    assertThatThrownBy { client.insertFeedbackChecked(listOf(GorseFeedback("like", "user", "item", "now"))) }
      .isInstanceOf(Exception::class.java)
  }

  @ParameterizedTest
  @ValueSource(ints = [200, 404])
  fun `ready Gorse accepts successful or missing Item authentication probe`(status: Int) {
    probeResponseCode = status

    val result = client.testConnection(baseUrl(), "secret", Duration.ofSeconds(1))

    assertThat(result).isEqualTo(GorseHealthStatus(Ready = true, DataStoreConnected = true, CacheStoreConnected = true))
    assertThat(probeApiKey.get()).isEqualTo("secret")
    assertThat(connectionTestRequests).containsExactly(
      "GET /api/health/ready",
      "GET /api/item/__komga_connection_test__",
    )
  }

  @Test
  fun `unready Gorse returns health status without authentication probe`() {
    healthResponseBody = """{"Ready":false,"DataStoreConnected":false,"CacheStoreConnected":true}"""

    val result = client.testConnection(baseUrl(), "secret", Duration.ofSeconds(1))

    assertThat(result.Ready).isFalse()
    assertThat(result.DataStoreConnected).isFalse()
    assertThat(connectionTestRequests).containsExactly("GET /api/health/ready")
  }

  @ParameterizedTest
  @ValueSource(ints = [401, 403])
  fun `rejected API key propagates authentication failure`(status: Int) {
    probeResponseCode = status

    val failure = catchThrowable { client.testConnection(baseUrl(), "wrong-secret", Duration.ofSeconds(1)) }

    assertThat(failure).isInstanceOf(WebClientResponseException::class.java)
    assertThat((failure as WebClientResponseException).statusCode.value()).isEqualTo(status)
  }

  @Test
  fun `connection test applies one timeout across both probes`() {
    hangHealthResponse = true

    assertThatThrownBy {
      client.testConnection(baseUrl(), "secret", Duration.ofMillis(100))
    }.isInstanceOf(IllegalStateException::class.java)
      .hasMessageContaining("Timeout")
  }

  @Test
  fun `unreachable Gorse propagates request failure`() {
    val unusedPort = ServerSocket(0).use { it.localPort }

    assertThatThrownBy {
      client.testConnection("http://127.0.0.1:$unusedPort", "secret", Duration.ofSeconds(1))
    }.isInstanceOf(WebClientRequestException::class.java)
  }

  private fun baseUrl(): String = "http://${server.address.hostString}:${server.address.port}"

  private fun writeResponse(
    exchange: HttpExchange,
    status: Int,
    body: String,
  ) {
    val bytes = body.toByteArray()
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    exchange.responseBody.use { if (bytes.isNotEmpty()) it.write(bytes) }
  }

  companion object {
    private const val RECOMMENDATIONS_RESPONSE = """[{"Id":"first","Score":0.9},{"Id":"second","Score":0.5}]"""
    private const val READY_RESPONSE =
      """{"Ready":true,"DataStoreConnected":true,"CacheStoreConnected":true,"DataStoreError":null,"CacheStoreError":null}"""
  }
}
