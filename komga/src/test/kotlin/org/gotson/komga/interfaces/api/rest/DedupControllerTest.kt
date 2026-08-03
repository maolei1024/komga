package org.gotson.komga.interfaces.api.rest

import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.application.tasks.TaskProcessor
import org.gotson.komga.application.tasks.TasksRepository
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
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

@SpringBootTest
@AutoConfigureMockMvc(printOnlyOnFailure = false)
class DedupControllerTest(
  @Autowired private val mockMvc: MockMvc,
  @Autowired private val dedupRepository: DedupRepository,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val tasksRepository: TasksRepository,
  @Autowired private val taskProcessor: TaskProcessor,
) {
  private val library = makeLibrary("dedup-controller")

  @BeforeEach
  fun setup() {
    taskProcessor.processTasks = false
    libraryRepository.insert(library)
  }

  @AfterEach
  fun cleanup() {
    tasksRepository.deleteAll()
    dedupRepository.deleteAllDedupData()
    libraryRepository.deleteAll()
    taskProcessor.processTasks = true
  }

  @Test
  @WithMockUser(roles = ["USER"])
  fun `non admin users cannot read dedup status`() {
    mockMvc.get("/api/v1/dedup/status").andExpect { status { isForbidden() } }
    mockMvc
      .post("/api/v1/dedup/cases/verify") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"cases":[{"caseId":"case","expectedRevision":1}]}"""
      }.andExpect { status { isForbidden() } }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `bulk verification reports missing cases and validates bounded unique input`() {
    mockMvc
      .post("/api/v1/dedup/cases/verify") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"cases":[{"caseId":"missing","expectedRevision":1}]}"""
      }.andExpect {
        status { isAccepted() }
        jsonPath("$.requested") { value(1) }
        jsonPath("$.queued") { value(0) }
        jsonPath("$.failed") { value(1) }
        jsonPath("$.results[0].status") { value("NOT_FOUND") }
      }

    mockMvc
      .post("/api/v1/dedup/cases/verify") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"cases":[{"caseId":"duplicate","expectedRevision":1},{"caseId":"duplicate","expectedRevision":1}]}"""
      }.andExpect { status { isBadRequest() } }

    val oversized =
      (1..101).joinToString(prefix = "{\"cases\":[", postfix = "]}") { index ->
        "{\"caseId\":\"case-$index\",\"expectedRevision\":1}"
      }
    mockMvc
      .post("/api/v1/dedup/cases/verify") {
        contentType = MediaType.APPLICATION_JSON
        content = oversized
      }.andExpect { status { isBadRequest() } }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `admin can configure and request an exact duplicate scan`() {
    mockMvc
      .put("/api/v1/dedup/settings") {
        contentType = MediaType.APPLICATION_JSON
        content =
          """
          {
            "libraries": [{
              "libraryId": "${library.id}",
              "enabled": true,
              "batchSize": 12,
              "maxDurationSeconds": 30,
              "quietPeriodSeconds": 60,
              "completionStabilitySeconds": 120
            }]
          }
          """.trimIndent()
      }.andExpect {
        status { isOk() }
        jsonPath("$.libraries[0].enabled") { value(true) }
        jsonPath("$.libraries[0].batchSize") { value(12) }
      }

    assertThat(dedupRepository.findLibrarySettings(library.id)?.enabled).isTrue
    assertThat(dedupRepository.findAllWork()).hasSize(1)

    mockMvc
      .post("/api/v1/dedup/scans") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"libraryIds":["${library.id}"]}"""
      }.andExpect {
        status { isAccepted() }
        jsonPath("$.requestedLibraries") { value(1) }
      }

    val work = dedupRepository.findAllWork().single()
    assertThat(work.desiredRevision).isEqualTo(2)

    mockMvc.get("/api/v1/dedup/cases").andExpect {
      status { isOk() }
      jsonPath("$.content.length()") { value(0) }
    }

    mockMvc.get("/api/v1/dedup/decisions/anything").andExpect { status { isNotFound() } }
    mockMvc.post("/api/v1/dedup/decisions/anything/execute").andExpect { status { isConflict() } }
  }

  @Test
  @WithMockCustomUser(roles = ["ADMIN"])
  fun `disabled scan interval is rejected in favor of the library enable switch`() {
    mockMvc
      .put("/api/v1/dedup/settings") {
        contentType = MediaType.APPLICATION_JSON
        content =
          """
          {"libraries":[{"libraryId":"${library.id}","enabled":true,"scanInterval":"DISABLED"}]}
          """.trimIndent()
      }.andExpect { status { isBadRequest() } }
  }
}
