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
import org.gotson.komga.domain.service.DedupSuggestionPlanner
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

  @MockkBean
  private lateinit var suggestionPlanner: DedupSuggestionPlanner

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `cluster base list never evaluates live processing eligibility`() {
    mockMvc.get("/api/v1/dedup/clusters?page=0&size=100&status=UNPROCESSED").andExpect { status { isOk() } }

    verify(exactly = 0) { suggestionPlanner.evaluate(any<org.gotson.komga.domain.model.DedupClusterWithMembers>()) }
  }

  @Test
  @WithMockUser(roles = ["USER"])
  fun `non administrators cannot read or mutate Dedup resources`() {
    mockMvc.get("/api/v1/dedup/status").andExpect { status { isForbidden() } }
    mockMvc
      .post("/api/v1/dedup/clusters/verify") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"clusters":[{"clusterId":"cluster","expectedRevision":1}]}"""
      }.andExpect { status { isForbidden() } }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `bulk cluster verification reports missing clusters and rejects duplicate or oversized input`() {
    mockMvc
      .post("/api/v1/dedup/clusters/verify") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"clusters":[{"clusterId":"missing","expectedRevision":1}]}"""
      }.andExpect {
        status { isAccepted() }
        jsonPath("$.requestedClusters") { value(1) }
        jsonPath("$.queuedClusters") { value(0) }
        jsonPath("$.failedClusters") { value(1) }
        jsonPath("$.results[0].status") { value("NOT_FOUND") }
      }

    mockMvc
      .post("/api/v1/dedup/clusters/verify") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"clusters":[{"clusterId":"duplicate","expectedRevision":1},{"clusterId":"duplicate","expectedRevision":1}]}"""
      }.andExpect { status { isBadRequest() } }

    val oversized =
      (1..101).joinToString(prefix = "{\"clusters\":[", postfix = "]}") { index ->
        "{\"clusterId\":\"cluster-$index\",\"expectedRevision\":1}"
      }
    mockMvc
      .post("/api/v1/dedup/clusters/verify") {
        contentType = MediaType.APPLICATION_JSON
        content = oversized
      }.andExpect { status { isBadRequest() } }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `custom keep-all returns 201 with a processed resolution`() {
    every { resolutionLifecycle.createCustom("cluster", 1, "state", any(), emptySet(), any()) } returns resolution()

    mockMvc
      .post("/api/v1/dedup/clusters/cluster/resolutions/custom") {
        contentType = MediaType.APPLICATION_JSON
        content =
          """
          {
            "expectedRevision": 1,
            "stateRevision": "state",
            "members": [
              {"bookId": "A", "action": "KEEP"},
              {"bookId": "B", "action": "KEEP"}
            ],
            "acknowledgedReasonCodes": []
          }
          """.trimIndent()
      }.andExpect {
        status { isCreated() }
        jsonPath("$.id") { value("resolution") }
        jsonPath("$.state") { value("PROCESSED") }
      }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `resolution conflicts return structured 409 bodies`() {
    every { resolutionLifecycle.createCustom("stale", any(), any(), any(), any(), any()) } throws
      DedupResolutionValidationException("CLUSTER_STALE", "Cluster revision changed")
    every { resolutionLifecycle.createCustom("partial", any(), any(), any(), any(), any()) } throws
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
  fun `abandon endpoint returns the preserved audit in abandoned state`() {
    every { resolutionLifecycle.abandon("resolution") } returns resolution().copy(state = DedupResolutionState.ABANDONED)

    mockMvc.post("/api/v1/dedup/resolutions/resolution/abandon").andExpect {
      status { isOk() }
      jsonPath("$.id") { value("resolution") }
      jsonPath("$.state") { value("ABANDONED") }
    }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `duplicate acknowledgements are 400 and legacy pair routes are absent`() {
    mockMvc
      .post("/api/v1/dedup/clusters/cluster/resolutions/custom") {
        contentType = MediaType.APPLICATION_JSON
        content = keepAllRequest("[\"RISK\",\"RISK\"]")
      }.andExpect { status { isBadRequest() } }

    mockMvc.get("/api/v1/dedup/cases").andExpect { status { isNotFound() } }
    mockMvc.post("/api/v1/dedup/overrides").andExpect { status { isNotFound() } }
  }

  private fun keepAllRequest(acknowledgements: String = "[]") =
    """
    {
      "expectedRevision": 1,
      "stateRevision": "state",
      "members": [
        {"bookId": "A", "action": "KEEP"},
        {"bookId": "B", "action": "KEEP"}
      ],
      "acknowledgedReasonCodes": $acknowledgements
    }
    """.trimIndent()

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
      ruleVersion = 1,
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
