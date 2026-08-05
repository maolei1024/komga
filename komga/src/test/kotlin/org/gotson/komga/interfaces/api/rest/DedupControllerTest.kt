package org.gotson.komga.interfaces.api.rest

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.service.DedupResolutionExecutionException
import org.gotson.komga.domain.service.DedupResolutionLifecycle
import org.gotson.komga.domain.service.DedupResolutionValidationException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class DedupControllerTest(
  @Autowired private val mockMvc: MockMvc,
) {
  @MockkBean
  private lateinit var resolutionLifecycle: DedupResolutionLifecycle

  @Test
  @WithMockUser(roles = ["USER"])
  fun `non administrators cannot read or mutate Dedup resources`() {
    mockMvc.get("/api/v1/dedup/status").andExpect { status { isForbidden() } }
    mockMvc
      .post("/api/v1/dedup/clusters/cluster/resolutions/custom") {
        contentType = MediaType.APPLICATION_JSON
        content = keepAllRequest()
      }.andExpect { status { isForbidden() } }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `custom contract contains only revision and delete IDs`() {
    every { resolutionLifecycle.createCustom("cluster", 1, listOf("B"), any()) } returns resolution()

    mockMvc
      .post("/api/v1/dedup/clusters/cluster/resolutions/custom") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"expectedRevision":1,"deleteBookIds":["B"]}"""
      }.andExpect {
        status { isCreated() }
        jsonPath("$.id") { value("resolution") }
        jsonPath("$.state") { value("PROCESSED") }
      }

    verify(exactly = 1) { resolutionLifecycle.createCustom("cluster", 1, listOf("B"), any()) }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `empty delete IDs are accepted as keep-all`() {
    every { resolutionLifecycle.createCustom("cluster", 1, emptyList(), any()) } returns resolution()

    mockMvc
      .post("/api/v1/dedup/clusters/cluster/resolutions/custom") {
        contentType = MediaType.APPLICATION_JSON
        content = keepAllRequest()
      }.andExpect { status { isCreated() } }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `resolution conflicts return structured 409 bodies`() {
    every { resolutionLifecycle.createCustom("stale", any(), any(), any()) } throws
      DedupResolutionValidationException("CLUSTER_STALE", "Cluster revision changed")
    every { resolutionLifecycle.createCustom("partial", any(), any(), any()) } throws
      DedupResolutionExecutionException("resolution-partial", "DELETE_FAILED", true, "Second unlink failed")

    mockMvc
      .post("/api/v1/dedup/clusters/stale/resolutions/custom") {
        contentType = MediaType.APPLICATION_JSON
        content = keepAllRequest()
      }.andExpect {
        status { isConflict() }
        jsonPath("$.code") { value("CLUSTER_STALE") }
        jsonPath("$.partial") { value(false) }
      }

    mockMvc
      .post("/api/v1/dedup/clusters/partial/resolutions/custom") {
        contentType = MediaType.APPLICATION_JSON
        content = keepAllRequest()
      }.andExpect {
        status { isConflict() }
        jsonPath("$.code") { value("DELETE_FAILED") }
        jsonPath("$.resolutionId") { value("resolution-partial") }
        jsonPath("$.partial") { value(true) }
      }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `manual verification eligibility processing and abandon endpoints are absent`() {
    mockMvc.post("/api/v1/dedup/clusters/cluster/verify").andExpect { status { isNotFound() } }
    mockMvc.post("/api/v1/dedup/clusters/verify").andExpect { status { isMethodNotAllowed() } }
    mockMvc.post("/api/v1/dedup/clusters/eligibility").andExpect { status { isMethodNotAllowed() } }
    mockMvc.get("/api/v1/dedup/clusters/cluster/processing").andExpect { status { isNotFound() } }
    mockMvc.post("/api/v1/dedup/resolutions/resolution/abandon").andExpect { status { isNotFound() } }
  }

  private fun keepAllRequest() = """{"expectedRevision":1,"deleteBookIds":[]}"""

  private fun resolution(): DedupResolution {
    val now = LocalDateTime.now()
    return DedupResolution(
      id = "resolution",
      clusterId = "cluster",
      clusterRevision = 1,
      mode = DedupResolutionMode.CUSTOM,
      planRevision = "plan",
      planJson = "{}",
      evidenceJson = "{}",
      eligibilityJson = "{}",
      ruleVersion = 3,
      state = DedupResolutionState.PROCESSED,
      actorId = "admin",
      resultJson = "{}",
      leaseToken = "lease",
      leaseUntil = now,
      createdDate = now,
      lastModifiedDate = now,
      completedDate = now,
    )
  }
}
