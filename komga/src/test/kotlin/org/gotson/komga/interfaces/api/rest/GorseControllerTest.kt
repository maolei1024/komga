package org.gotson.komga.interfaces.api.rest

import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class GorseControllerTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val gorseSettings: GorseSettingsProvider,
  @Autowired private val serverSettingsDao: ServerSettingsDao,
) {
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
}
