package org.gotson.komga.domain.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupArchiveHashState
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupDeletionResultCode
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionMember
import org.gotson.komga.domain.model.DedupResolutionMemberState
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime

class DedupResolutionLifecycleTest {
  @TempDir
  lateinit var directory: Path

  @Test
  fun `empty delete IDs complete keep-all and never hash or delete a file`() {
    val context = context("A", "B")

    val queued = context.lifecycle.createCustom("cluster", 1, emptyList(), "admin")

    assertThat(queued.state).isEqualTo(DedupResolutionState.PROCESSING)
    verify(exactly = 1) { context.tasks.executeDedupResolution(queued.id, "library", any()) }
    verify(exactly = 0) { context.clusters.finalizeProcessed(any(), any(), any(), any(), any()) }
    val result = context.lifecycle.executeQueued(queued.id)
    assertThat(result.state).isEqualTo(DedupResolutionState.PROCESSED)
    assertThat(context.resolutions.findResolutionMembers(result.id)).allMatch {
      it.action == DedupResolutionAction.KEEP && it.state == DedupResolutionMemberState.COMPLETED
    }
    verify(exactly = 0) { context.deletion.captureStrongIdentity(any(), any()) }
    verify(exactly = 0) { context.deletion.deleteVerifiedBook(any(), any(), any()) }
    verify { context.clusters.finalizeProcessed("cluster", result.id, "admin", setOf("A", "B"), any()) }
  }

  @Test
  fun `custom deletion reuses verified archive hash and keeps strong identity deletion`() {
    val context = context("A", "B")
    context.successfulDeletion("B")

    val queued = context.lifecycle.createCustom("cluster", 1, listOf("B"), "admin")

    assertThat(queued.state).isEqualTo(DedupResolutionState.PROCESSING)
    verify(exactly = 0) { context.deletion.deleteVerifiedBook(any(), any(), any()) }
    val result = context.lifecycle.executeQueued(queued.id)
    val deleted = context.resolutions.findResolutionMembers(result.id).single { it.bookId == "B" }
    assertThat(deleted.keeperBookId).isNull()
    assertThat(deleted.directRelationId).isNull()
    assertThat(deleted.expectedArchiveHash).isEqualTo("hash-B")
    assertThat(deleted.state).isEqualTo(DedupResolutionMemberState.COMPLETED)
    verify(exactly = 0) { context.deletion.captureStrongIdentity(match { it.id == "A" }, any()) }
    verify(exactly = 0) { context.deletion.captureStrongIdentity(match { it.id == "B" }, any()) }
    verify(exactly = 1) { context.deletion.deleteVerifiedBook(match { it.id == "B" }, match { it.archiveHash == "hash-B" }, any()) }
  }

  @Test
  fun `queued execution resumes an interrupted completed preflight`() {
    val context = context("A", "B")
    context.successfulDeletion("B")
    val queued = context.lifecycle.createCustom("cluster", 1, listOf("B"), "admin")
    context.resolutions.updateResolutionMember(
      queued.id,
      "A",
      setOf(DedupResolutionMemberState.PLANNED),
      DedupResolutionMemberState.PREFLIGHTED,
    )
    context.resolutions.updateResolutionMember(
      queued.id,
      "B",
      setOf(DedupResolutionMemberState.PLANNED),
      DedupResolutionMemberState.PREFLIGHTED,
      expectedPath = context.paths.getValue("B").toString(),
      expectedSize = Files.size(context.paths.getValue("B")),
      expectedArchiveHash = "hash-B",
    )

    val result = context.lifecycle.executeQueued(queued.id)

    assertThat(result.state).isEqualTo(DedupResolutionState.PROCESSED)
    verify(exactly = 0) { context.deletion.precheck(any()) }
    verify(exactly = 1) { context.deletion.deleteVerifiedBook(match { it.id == "B" }, any(), any()) }
  }

  @Test
  fun `queued execution resumes after cluster finalization`() {
    val context = context("A", "B")
    val queued = context.lifecycle.createCustom("cluster", 1, emptyList(), "admin")
    listOf("A", "B").forEach { id ->
      context.resolutions.updateResolutionMember(
        queued.id,
        id,
        setOf(DedupResolutionMemberState.PLANNED),
        DedupResolutionMemberState.COMPLETED,
      )
    }
    context.clusterStatus = DedupClusterStatus.PROCESSED
    context.lastResolutionId = queued.id

    val result = context.lifecycle.executeQueued(queued.id)

    assertThat(result.state).isEqualTo(DedupResolutionState.PROCESSED)
    verify(exactly = 0) { context.clusters.finalizeProcessed(any(), any(), any(), any(), any()) }
  }

  @Test
  fun `task persistence failure reopens the cluster without deleting`() {
    val context = context("A", "B")
    every { context.tasks.executeDedupResolution(any(), "library", any()) } throws IllegalStateException("tasks database unavailable")

    val failure =
      catchThrowableOfType(
        { context.lifecycle.createCustom("cluster", 1, listOf("B"), "admin") },
        DedupResolutionExecutionException::class.java,
      )

    assertThat(failure.code).isEqualTo("QUEUE_FAILED")
    assertThat(failure.partial).isFalse()
    assertThat(context.clusterStatus).isEqualTo(DedupClusterStatus.UNPROCESSED)
    assertThat(context.resolutions.findResolution(failure.resolutionId!!)!!.state).isEqualTo(DedupResolutionState.NEEDS_ATTENTION)
    verify(exactly = 0) { context.deletion.deleteVerifiedBook(any(), any(), any()) }
  }

  @Test
  fun `delete target without a current persisted archive hash fails before unlink`() {
    val context = context("A", "B")
    every { context.cover.currentSourceIdentity("B") } returns identity("B").copy(archiveHashState = DedupArchiveHashState.MISSING, archiveHash = null)
    every { context.deletion.precheck(match { it.id == "B" }) } returns available("B")

    val queued = context.lifecycle.createCustom("cluster", 1, listOf("B"), "admin")
    val failure =
      catchThrowableOfType(
        { context.lifecycle.executeQueued(queued.id) },
        DedupResolutionExecutionException::class.java,
      )

    assertThat(failure.partial).isFalse()
    assertThat(failure.message).contains("persisted archive hash")
    verify(exactly = 0) { context.deletion.captureStrongIdentity(any(), any()) }
    verify(exactly = 0) { context.deletion.deleteVerifiedBook(any(), any(), any()) }
  }

  @Test
  fun `all delete targets finish preflight before any unlink`() {
    val context = context("A", "B", "C")
    every { context.deletion.precheck(match { it.id == "B" }) } returns available("B")
    every { context.deletion.precheck(match { it.id == "C" }) } returns
      DedupFilePrecheck(DedupFilePrecheckStatus.UNAVAILABLE, context.paths.getValue("C").toString(), Files.size(context.paths.getValue("C")), detail = "not writable")

    val queued = context.lifecycle.createCustom("cluster", 1, listOf("B", "C"), "admin")
    val failure =
      catchThrowableOfType(
        { context.lifecycle.executeQueued(queued.id) },
        DedupResolutionExecutionException::class.java,
      )

    assertThat(failure.partial).isFalse()
    assertThat(context.clusterStatus).isEqualTo(DedupClusterStatus.UNPROCESSED)
    verify(exactly = 0) { context.deletion.deleteVerifiedBook(any(), any(), any()) }
  }

  @Test
  fun `partial deletion retry never unlinks a successful member twice`() {
    val context = context("A", "B", "C")
    var cAttempts = 0
    listOf("B", "C").forEach { id ->
      every { context.deletion.precheck(match { it.id == id }) } returns available(id)
      every { context.deletion.captureStrongIdentity(match { it.id == id }, any()) } answers { context.identityOnDisk(id) }
    }
    every { context.deletion.deleteVerifiedBook(match { it.id == "B" }, any(), any()) } answers {
      Files.deleteIfExists(context.paths.getValue("B"))
      thirdArg<() -> Unit>().invoke()
      context.softDeleted += "B"
      DedupPhysicalDeletionResult(DedupDeletionResultCode.DELETED, true, true)
    }
    every { context.deletion.deleteVerifiedBook(match { it.id == "C" }, any(), any()) } answers {
      if (cAttempts++ == 0) {
        DedupPhysicalDeletionResult(DedupDeletionResultCode.DELETE_FAILED, false, false, "transient unlink failure")
      } else {
        Files.deleteIfExists(context.paths.getValue("C"))
        thirdArg<() -> Unit>().invoke()
        context.softDeleted += "C"
        DedupPhysicalDeletionResult(DedupDeletionResultCode.DELETED, true, true)
      }
    }
    every { context.deletion.confirmPathAbsentAndSoftDelete(match { it.id == "B" }) } returns
      DedupPhysicalDeletionResult(DedupDeletionResultCode.ALREADY_DELETED_BY_THIS_RESOLUTION, true, true)

    val queued = context.lifecycle.createCustom("cluster", 1, listOf("B", "C"), "admin")
    val failure =
      catchThrowableOfType(
        { context.lifecycle.executeQueued(queued.id) },
        DedupResolutionExecutionException::class.java,
      )

    assertThat(failure.partial).isTrue()
    assertThat(context.resolutions.findResolution(failure.resolutionId!!)!!.state).isEqualTo(DedupResolutionState.PARTIALLY_COMPLETED)

    val retryQueued = context.lifecycle.retry(failure.resolutionId!!)

    assertThat(retryQueued.state).isEqualTo(DedupResolutionState.PROCESSING)
    val result = context.lifecycle.executeQueued(retryQueued.id)
    assertThat(result.state).isEqualTo(DedupResolutionState.PROCESSED)
    verify(exactly = 1) { context.deletion.deleteVerifiedBook(match { it.id == "B" }, any(), any()) }
    verify(exactly = 2) { context.deletion.deleteVerifiedBook(match { it.id == "C" }, any(), any()) }
    verify(exactly = 1) { context.deletion.confirmPathAbsentAndSoftDelete(match { it.id == "B" }) }
  }

  @Test
  fun `deleting every active member is rejected before cluster claim`() {
    val context = context("A", "B")

    org.assertj.core.api.Assertions
      .assertThatThrownBy {
        context.lifecycle.createCustom("cluster", 1, listOf("A", "B"), "admin")
      }.isInstanceOf(DedupResolutionValidationException::class.java)
      .hasMessageContaining("retained")
    verify(exactly = 0) { context.dedup.claimCluster(any(), any(), any(), any()) }
  }

  private fun available(id: String): DedupFilePrecheck {
    val path = directory.resolve("$id.cbz")
    return DedupFilePrecheck(DedupFilePrecheckStatus.AVAILABLE, path.toString(), Files.size(path), Files.size(path))
  }

  private fun context(vararg ids: String): TestContext {
    val dedup = mockk<DedupRepository>()
    val resolutions = FakeResolutionRepository()
    val books = mockk<BookRepository>()
    val suggestions = mockk<DedupSuggestionPlanner>()
    val cover = mockk<DedupCoverLifecycle>()
    val deletion = mockk<DedupPhysicalBookDeletionLifecycle>()
    val clusters = mockk<DedupClusterLifecycle>()
    val tasks = mockk<TaskEmitter>()
    val value = cluster(*ids)
    val paths = ids.associateWith { Files.writeString(directory.resolve("$it.cbz"), "archive-$it") }
    val context = TestContext(dedup, resolutions, books, suggestions, cover, deletion, clusters, tasks, value, paths)

    every { dedup.findCluster("cluster") } answers { value.copy(cluster = value.cluster.copy(status = context.clusterStatus, lastResolutionId = context.lastResolutionId)) }
    every { clusters.rebuildLibrary("library", any()) } returns 1
    every { clusters.currentFingerprints(any()) } returns ClusterFingerprints("topology", "evidence", "state")
    every { dedup.claimCluster("cluster", 1, "state", any()) } answers {
      context.clusterStatus = DedupClusterStatus.PROCESSING
      true
    }
    every { dedup.updateClusterState(any(), any(), any(), any(), any(), any()) } answers {
      context.clusterStatus = thirdArg()
      (invocation.args[3] as String?)?.let { context.lastResolutionId = it }
      true
    }
    every { clusters.currentReviewRelations(any()) } returns emptyList()
    every { tasks.executeDedupResolution(any(), "library", any()) } just Runs
    every { clusters.finalizeProcessed("cluster", any(), "admin", any(), any()) } answers {
      context.clusterStatus = DedupClusterStatus.PROCESSED
      value.cluster.copy(status = DedupClusterStatus.PROCESSED, lastResolutionId = secondArg(), processedDate = LocalDateTime.now())
    }
    ids.forEach { id ->
      every { books.findByIdOrNull(id) } answers {
        book(id, paths.getValue(id)).let { if (id in context.softDeleted) it.copy(deletedDate = LocalDateTime.now()) else it }
      }
      every { cover.currentSourceIdentity(id) } returns identity(id)
    }
    return context
  }

  private fun cluster(vararg ids: String): DedupClusterWithMembers {
    val now = LocalDateTime.now()
    val value = DedupCluster("cluster", "library", 1, DedupClusterStatus.UNPROCESSED, true, ids.first(), "topology", "evidence", "state", null, null, null, null, now, now, null)
    return DedupClusterWithMembers(value, ids.map { DedupClusterMember("cluster", it, true, "content-$it", "cover-$it", "metadata-$it", "scope-$it", now, now) })
  }

  private fun identity(id: String) =
    DedupSourceIdentity(
      id,
      "series-$id",
      "library",
      "content-$id",
      "cover-$id",
      "metadata-$id",
      "scope-$id",
      10,
      DedupArchiveHashState.READY,
      "hash-$id",
    )

  private fun book(
    id: String,
    path: Path,
  ) = Book(id, path.toUri().toURL(), LocalDateTime.MIN, runCatching { Files.size(path) }.getOrDefault(9), id = id, seriesId = "series-$id", libraryId = "library")

  private inner class TestContext(
    val dedup: DedupRepository,
    val resolutions: FakeResolutionRepository,
    val books: BookRepository,
    val suggestions: DedupSuggestionPlanner,
    val cover: DedupCoverLifecycle,
    val deletion: DedupPhysicalBookDeletionLifecycle,
    val clusters: DedupClusterLifecycle,
    val tasks: TaskEmitter,
    val cluster: DedupClusterWithMembers,
    val paths: Map<String, Path>,
  ) {
    var clusterStatus = DedupClusterStatus.UNPROCESSED
    var lastResolutionId: String? = null
    val softDeleted = mutableSetOf<String>()
    val lifecycle = DedupResolutionLifecycle(dedup, resolutions, books, suggestions, cover, deletion, clusters, tasks, jacksonObjectMapper().findAndRegisterModules())

    fun identityOnDisk(id: String) = DedupStrongFileIdentity(paths.getValue(id).toString(), Files.size(paths.getValue(id)), "hash-$id")

    fun successfulDeletion(id: String) {
      every { deletion.precheck(match { it.id == id }) } returns available(id)
      every { deletion.deleteVerifiedBook(match { it.id == id }, any(), any()) } answers {
        Files.deleteIfExists(paths.getValue(id))
        thirdArg<() -> Unit>().invoke()
        softDeleted += id
        DedupPhysicalDeletionResult(DedupDeletionResultCode.DELETED, true, true)
      }
    }
  }
}

private class FakeResolutionRepository : DedupResolutionRepository {
  private val resolutions = linkedMapOf<String, DedupResolution>()
  private val members = linkedMapOf<String, MutableList<DedupResolutionMember>>()

  override fun insertResolution(
    resolution: DedupResolution,
    members: Collection<DedupResolutionMember>,
  ) {
    resolutions[resolution.id] = resolution
    this.members[resolution.id] = members.toMutableList()
  }

  override fun findResolution(resolutionId: String) = resolutions[resolutionId]

  override fun findResolutions(
    offset: Int,
    limit: Int,
  ) = resolutions.values.drop(offset).take(limit)

  override fun countResolutions() = resolutions.size.toLong()

  override fun findProcessedResolutions(
    offset: Int,
    limit: Int,
  ) = resolutions.values
    .filter { it.state == DedupResolutionState.PROCESSED }
    .drop(offset)
    .take(limit)

  override fun countProcessedResolutions() = resolutions.values.count { it.state == DedupResolutionState.PROCESSED }.toLong()

  override fun countResolutionsByState() = resolutions.values.groupingBy { it.state }.eachCount()

  override fun findResolutionMembers(resolutionId: String) = members[resolutionId].orEmpty().toList()

  override fun hasActiveResolutionForBooks(bookIds: Set<String>) = resolutions.values.any { it.state == DedupResolutionState.PROCESSING && members[it.id].orEmpty().any { member -> member.bookId in bookIds } }

  override fun updateResolution(
    resolutionId: String,
    expectedStates: Set<DedupResolutionState>,
    state: DedupResolutionState,
    resultJson: String,
    completedDate: LocalDateTime?,
    leaseToken: String?,
    leaseUntil: LocalDateTime?,
    now: LocalDateTime,
  ): Boolean {
    val current = resolutions[resolutionId] ?: return false
    if (current.state !in expectedStates) return false
    resolutions[resolutionId] = current.copy(state = state, resultJson = resultJson, completedDate = completedDate, leaseToken = leaseToken ?: current.leaseToken, leaseUntil = leaseUntil ?: current.leaseUntil, lastModifiedDate = now)
    return true
  }

  override fun updateResolutionMember(
    resolutionId: String,
    bookId: String,
    expectedStates: Set<DedupResolutionMemberState>,
    state: DedupResolutionMemberState,
    expectedPath: String?,
    expectedSize: Long?,
    expectedArchiveHash: String?,
    resultCode: String?,
    resultJson: String?,
    lastError: String?,
    now: LocalDateTime,
  ): Boolean {
    val list = members[resolutionId] ?: return false
    val index = list.indexOfFirst { it.bookId == bookId && it.state in expectedStates }
    if (index < 0) return false
    val current = list[index]
    list[index] =
      current.copy(
        state = state,
        expectedPath = expectedPath ?: current.expectedPath,
        expectedSize = expectedSize ?: current.expectedSize,
        expectedArchiveHash = expectedArchiveHash ?: current.expectedArchiveHash,
        resultCode = resultCode ?: current.resultCode,
        resultJson = resultJson ?: current.resultJson,
        lastError = lastError,
        lastModifiedDate = now,
      )
    return true
  }

  override fun releaseExpiredResolutionLeases(now: LocalDateTime) = 0
}
