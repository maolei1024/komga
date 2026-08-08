package org.gotson.komga.interfaces.api.rest

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.service.DedupResolutionExecutionException
import org.gotson.komga.domain.service.DedupResolutionLifecycle
import org.gotson.komga.domain.service.DedupResolutionValidationException
import org.gotson.komga.infrastructure.jooq.main.DedupDao
import org.gotson.komga.infrastructure.jooq.main.LibraryDao
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDateTime
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class DedupControllerTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val libraryDao: LibraryDao,
  @Autowired private val dedupDao: DedupDao,
) {
  @MockkBean
  private lateinit var resolutionLifecycle: DedupResolutionLifecycle

  @Test
  @WithMockUser(roles = ["USER"])
  fun `non administrators cannot read or mutate Dedup resources`() {
    mockMvc.get("/api/v1/dedup/status").andExpect { status { isForbidden() } }
    mockMvc
      .put("/api/v1/dedup/settings") {
        contentType = MediaType.APPLICATION_JSON
        content =
          """
          {"libraries":[{"libraryId":"library","enabled":false,"paused":false,"scanInterval":"DAILY",
          "batchSize":100,"maxDurationSeconds":300,"quietPeriodSeconds":180,"coverCandidateDistance":15,"coverTopK":20}]}
          """.trimIndent()
      }.andExpect { status { isForbidden() } }
    mockMvc
      .post("/api/v1/dedup/clusters/cluster/resolutions/custom") {
        contentType = MediaType.APPLICATION_JSON
        content = keepAllRequest()
      }.andExpect { status { isForbidden() } }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `settings preserve automatic resolution when an older client omits the field`() {
    val library = makeLibrary("dedup-settings-${UUID.randomUUID()}")
    libraryDao.insert(library)
    dedupDao.saveLibrarySettings(DedupLibrarySettings(library.id, autoResolveSuggestions = true))

    mockMvc
      .put("/api/v1/dedup/settings") {
        contentType = MediaType.APPLICATION_JSON
        content =
          """
          {"libraries":[{"libraryId":"${library.id}","enabled":false,"paused":false,"scanInterval":"DAILY",
          "batchSize":100,"maxDurationSeconds":300,"quietPeriodSeconds":180,"coverCandidateDistance":15,"coverTopK":20}]}
          """.trimIndent()
      }.andExpect {
        status { isOk() }
        jsonPath("$.libraries[?(@.libraryId == '${library.id}')].autoResolveSuggestions") { value(org.hamcrest.Matchers.contains(true)) }
      }

    assertThat(dedupDao.findLibrarySettings(library.id)?.autoResolveSuggestions).isTrue()
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `settings expose and update automatic resolution`() {
    val library = makeLibrary("dedup-settings-auto-${UUID.randomUUID()}")
    libraryDao.insert(library)

    mockMvc
      .get("/api/v1/dedup/settings")
      .andExpect {
        status { isOk() }
        jsonPath("$.libraries[?(@.libraryId == '${library.id}')].autoResolveSuggestions") { value(org.hamcrest.Matchers.contains(false)) }
      }

    mockMvc
      .put("/api/v1/dedup/settings") {
        contentType = MediaType.APPLICATION_JSON
        content =
          """
          {"libraries":[{"libraryId":"${library.id}","enabled":false,"paused":false,"scanInterval":"DAILY",
          "batchSize":100,"maxDurationSeconds":300,"quietPeriodSeconds":180,"coverCandidateDistance":15,"coverTopK":20,
          "autoResolveSuggestions":true}]}
          """.trimIndent()
      }.andExpect {
        status { isOk() }
        jsonPath("$.libraries[?(@.libraryId == '${library.id}')].autoResolveSuggestions") { value(org.hamcrest.Matchers.contains(true)) }
      }

    assertThat(dedupDao.findLibrarySettings(library.id)?.autoResolveSuggestions).isTrue()
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
        jsonPath("$.state") { value("PROCESSING") }
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
      state = DedupResolutionState.PROCESSING,
      actorId = "admin",
      resultJson = "{}",
      leaseToken = "lease",
      leaseUntil = now,
      createdDate = now,
      lastModifiedDate = now,
      completedDate = null,
    )
  }
}
