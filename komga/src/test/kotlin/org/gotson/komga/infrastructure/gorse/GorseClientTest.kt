package org.gotson.komga.infrastructure.gorse

import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class GorseClientTest {
  private lateinit var server: HttpServer
  private lateinit var client: GorseClient
  private val apiVersion = AtomicReference<String>()
  private val query = AtomicReference<String>()
  private var responseCode = 200
  private var responseBody = """[{"Id":"first","Score":0.9},{"Id":"second","Score":0.5}]"""

  @BeforeEach
  fun setup() {
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/api/recommend/user") { exchange ->
      apiVersion.set(exchange.requestHeaders.getFirst("X-API-Version"))
      query.set(exchange.requestURI.query)
      val body = responseBody.toByteArray()
      exchange.responseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(responseCode, body.size.toLong())
      exchange.responseBody.use { it.write(body) }
    }
    server.start()

    val settings = mockk<GorseSettingsProvider>()
    every { settings.apiUrl } returns "http://${server.address.hostString}:${server.address.port}"
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
}
