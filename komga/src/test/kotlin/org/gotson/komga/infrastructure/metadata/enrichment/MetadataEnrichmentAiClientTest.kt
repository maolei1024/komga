package org.gotson.komga.infrastructure.metadata.enrichment

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class MetadataEnrichmentAiClientTest {
  private lateinit var server: HttpServer
  private val requestPath = AtomicReference<String>()
  private val authorization = AtomicReference<String>()

  @BeforeEach
  fun setup() {
    server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    server.createContext("/v1/chat/completions") { exchange ->
      requestPath.set(exchange.requestURI.path)
      authorization.set(exchange.requestHeaders.getFirst("Authorization"))
      respond(exchange, """{"choices":[{"message":{"content":"AAAA中文标题BBBB"}}]}""")
    }
    server.start()
  }

  @AfterEach
  fun tearDown() {
    server.stop(0)
  }

  @Test
  fun `root compatible endpoint uses v1 and parses marked title`() {
    val settings = mockk<MetadataEnrichmentSettingsProvider>()
    every { settings.aiEnabled } returns true
    every { settings.aiConfigured } returns true
    every { settings.aiBaseUrl } returns "HTTP://${server.address.hostString}:${server.address.port}"
    every { settings.aiModel } returns "model"
    every { settings.aiApiKey } returns "secret"
    every { settings.aiTimeoutSeconds } returns 5
    every { settings.aiMaxRetries } returns 0
    val client = MetadataEnrichmentAiClient(settings, WebClient.builder())

    assertThat(client.translate("原始简介")).isEqualTo("中文标题")
    assertThat(requestPath.get()).isEqualTo("/v1/chat/completions")
    assertThat(authorization.get()).isEqualTo("Bearer secret")
  }

  @Test
  fun `response parser rejects multiline refusals and malformed markers`() {
    val client = MetadataEnrichmentAiClient(mockk(relaxed = true), WebClient.builder())

    assertThat(client.parseTitle("「单行标题」")).isEqualTo("单行标题")
    assertThat(client.parseTitle("第一行\n第二行")).isNull()
    assertThat(client.parseTitle("抱歉，我无法翻译")).isNull()
    assertThat(client.parseTitle("AAAA缺少结束标记")).isNull()
  }

  private fun respond(
    exchange: HttpExchange,
    body: String,
  ) {
    val bytes = body.toByteArray()
    exchange.responseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
  }
}
