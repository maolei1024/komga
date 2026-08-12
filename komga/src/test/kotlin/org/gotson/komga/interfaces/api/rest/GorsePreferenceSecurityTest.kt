package org.gotson.komga.interfaces.api.rest

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class GorsePreferenceSecurityTest(
  @Autowired private val mockMvc: MockMvc,
) {
  @Test
  @WithAnonymousUser
  fun `anonymous users cannot read or update preferences`() {
    mockMvc
      .get("/api/v1/gorse/preference/series/series")
      .andExpect { status { isUnauthorized() } }
    mockMvc
      .put("/api/v1/gorse/preference/series/series") {
        contentType = org.springframework.http.MediaType.APPLICATION_JSON
        content = """{"preference":"DISLIKE"}"""
      }.andExpect { status { isUnauthorized() } }
  }
}
