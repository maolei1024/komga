package org.gotson.komga.interfaces.api.rest

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.infrastructure.gorse.GorseClient
import org.gotson.komga.infrastructure.gorse.GorseHealthStatus
import org.gotson.komga.infrastructure.gorse.GorseSettingsProvider
import org.gotson.komga.infrastructure.jooq.main.ServerSettingsDao
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.web.reactive.function.client.WebClientResponseException

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class GorseControllerTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val gorseSettings: GorseSettingsProvider,
  @Autowired private val serverSettingsDao: ServerSettingsDao,
) {
  @MockkBean
  private lateinit var gorseClient: GorseClient

  @BeforeEach
  fun setup() {
    gorseSettings.feedbackType = "read"
    gorseSettings.positiveFeedbackType = "like"
    gorseSettings.negativeFeedbackType = "dislike"
    gorseSettings.tagPenaltyExponent = 0.5
    serverSettingsDao.deleteAll()
  }

  @AfterEach
  fun cleanup() {
    gorseSettings.feedbackType = "read"
    gorseSettings.positiveFeedbackType = "like"
    gorseSettings.negativeFeedbackType = "dislike"
    gorseSettings.tagPenaltyExponent = 0.5
    serverSettingsDao.deleteAll()
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given admin user when retrieving Gorse settings then tag penalty exponent is returned`() {
    mockMvc
      .get("/api/v1/gorse")
      .andExpect {
        status { isOk() }
        jsonPath("$.tagPenaltyExponent") { value(0.5) }
        jsonPath("$.negativeFeedbackType") { value("dislike") }
      }
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      """{"feedbackType":""}""",
      """{"positiveFeedbackType":" "}""",
      """{"negativeFeedbackType":""}""",
      """{"positiveFeedbackType":"read"}""",
      """{"negativeFeedbackType":"like"}""",
      """{"feedbackType":"dislike"}""",
    ],
  )
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given invalid final feedback types when updating then bad request is returned`(jsonString: String) {
    mockMvc
      .patch("/api/v1/gorse") {
        contentType = MediaType.APPLICATION_JSON
        content = jsonString
      }.andExpect {
        status { isBadRequest() }
      }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given legacy padded feedback types when updating another setting then normalized values are persisted`() {
    gorseSettings.feedbackType = " read "
    gorseSettings.positiveFeedbackType = " like "
    gorseSettings.negativeFeedbackType = " dislike "

    mockMvc
      .patch("/api/v1/gorse") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"tagPenaltyExponent":0.75}"""
      }.andExpect {
        status { isNoContent() }
      }

    val reloaded = GorseSettingsProvider(serverSettingsDao)
    assertThat(reloaded.feedbackType).isEqualTo("read")
    assertThat(reloaded.positiveFeedbackType).isEqualTo("like")
    assertThat(reloaded.negativeFeedbackType).isEqualTo("dislike")
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given admin user when updating tag penalty exponent then value is persisted`() {
    mockMvc
      .patch("/api/v1/gorse") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"tagPenaltyExponent":0.75}"""
      }.andExpect {
        status { isNoContent() }
      }

    assertThat(gorseSettings.tagPenaltyExponent).isEqualTo(0.75)
    assertThat(GorseSettingsProvider(serverSettingsDao).tagPenaltyExponent).isEqualTo(0.75)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      """{"tagPenaltyExponent":-0.01}""",
      """{"tagPenaltyExponent":1.01}""",
      """{"tagPenaltyExponent":"NaN"}""",
    ],
  )
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given invalid tag penalty exponent when updating then bad request is returned`(jsonString: String) {
    mockMvc
      .patch("/api/v1/gorse") {
        contentType = MediaType.APPLICATION_JSON
        content = jsonString
      }.andExpect {
        status { isBadRequest() }
      }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given admin user when testing a ready authenticated Gorse then status is returned`() {
    every { gorseClient.testConnection("http://gorse:8088", "secret") } returns
      GorseHealthStatus(Ready = true, DataStoreConnected = true, CacheStoreConnected = true)

    mockMvc
      .post("/api/v1/gorse/test-connection") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"apiUrl":" http://gorse:8088 ","apiKey":"secret"}"""
      }.andExpect {
        status { isOk() }
        jsonPath("$.ready") { value(true) }
        jsonPath("$.dataStoreConnected") { value(true) }
        jsonPath("$.cacheStoreConnected") { value(true) }
        jsonPath("$.apiAuthenticated") { value(true) }
      }

    verify(exactly = 1) { gorseClient.testConnection("http://gorse:8088", "secret") }
  }

  @ParameterizedTest
  @ValueSource(strings = ["", "not-a-url", "ftp://gorse:8088", "http:///missing-host"])
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given invalid Gorse URL when testing connection then bad request is returned`(apiUrl: String) {
    mockMvc
      .post("/api/v1/gorse/test-connection") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"apiUrl":"$apiUrl","apiKey":""}"""
      }.andExpect {
        status { isBadRequest() }
      }

    verify(exactly = 0) { gorseClient.testConnection(any(), any()) }
  }

  @Test
  @WithMockCustomUser(roles = ["USER"])
  fun `given non admin user when testing Gorse then forbidden is returned`() {
    mockMvc
      .post("/api/v1/gorse/test-connection") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"apiUrl":"http://gorse:8088","apiKey":"secret"}"""
      }.andExpect {
        status { isForbidden() }
      }

    verify(exactly = 0) { gorseClient.testConnection(any(), any()) }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given unready Gorse when testing connection then safe bad gateway is returned`() {
    every { gorseClient.testConnection(any(), any()) } returns
      GorseHealthStatus(Ready = false, DataStoreConnected = false, CacheStoreConnected = true)

    mockMvc
      .post("/api/v1/gorse/test-connection") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"apiUrl":"http://gorse:8088","apiKey":"secret"}"""
      }.andExpect {
        status { isBadGateway() }
        jsonPath("$.message") { value("Gorse 尚未就绪") }
      }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given rejected API key when testing connection then safe bad gateway is returned`() {
    every { gorseClient.testConnection(any(), any()) } throws
      WebClientResponseException.create(
        HttpStatus.UNAUTHORIZED.value(),
        HttpStatus.UNAUTHORIZED.reasonPhrase,
        HttpHeaders.EMPTY,
        "sensitive upstream response".toByteArray(),
        null,
      )

    mockMvc
      .post("/api/v1/gorse/test-connection") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"apiUrl":"http://gorse:8088","apiKey":"wrong-secret"}"""
      }.andExpect {
        status { isBadGateway() }
        jsonPath("$.message") { value("Gorse API 密钥验证失败") }
        content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("sensitive upstream response"))) }
        content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("wrong-secret"))) }
      }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `given unreachable Gorse when testing connection then safe bad gateway is returned`() {
    every { gorseClient.testConnection(any(), any()) } throws IllegalStateException("connection details")

    mockMvc
      .post("/api/v1/gorse/test-connection") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"apiUrl":"http://gorse:8088","apiKey":"secret"}"""
      }.andExpect {
        status { isBadGateway() }
        jsonPath("$.message") { value("无法连接到 Gorse") }
        content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("connection details"))) }
      }
  }
}
