package org.gotson.komga.domain.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupDeletionResultCode
import org.gotson.komga.domain.model.DedupEligibilityReport
import org.gotson.komga.domain.model.DedupLocalStateSnapshot
import org.gotson.komga.domain.model.DedupPlanMember
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionMember
import org.gotson.komga.domain.model.DedupResolutionMemberState
import org.gotson.komga.domain.model.DedupResolutionPlan
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.gotson.komga.infrastructure.gorse.GorseDesiredStateLifecycle
import org.gotson.komga.infrastructure.gorse.GorseSyncNowResult
import org.gotson.komga.infrastructure.gorse.GorseSyncNowState
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
  fun `keep-all resolution completes synchronously without hashing deleting or contacting Gorse`() {
    val dedup = mockk<DedupRepository>()
    val resolutions = FakeResolutionRepository()
    val books = mockk<BookRepository>()
    val suggestions = mockk<DedupSuggestionPlanner>()
    val policy = mockk<DedupEligibilityPolicy>()
    val localState = mockk<DedupLocalStateLifecycle>()
    val cover = mockk<DedupCoverLifecycle>()
    val deletion = mockk<DedupPhysicalBookDeletionLifecycle>()
    val clusters = mockk<DedupClusterLifecycle>()
    val gorse = mockk<GorseDesiredStateLifecycle>()
    val value = cluster()
    val plan = DedupResolutionPlan("plan", listOf(DedupPlanMember("A", DedupResolutionAction.KEEP), DedupPlanMember("B", DedupResolutionAction.KEEP)))
    val report = DedupEligibilityReport(false, false, true, false, 1, "state", null, LocalDateTime.now(), emptyList(), emptyList(), emptyList())
    val snapshots = listOf("A", "B").associateWith { DedupLocalStateSnapshot(it, "local-$it", emptySet(), emptyMap()) }
    every { policy.validateCustom(any(), any(), any(), any(), any()) } returns plan
    every { suggestions.evaluate("cluster") } returns DedupSuggestion(null, report, snapshots)
    every { dedup.findCluster("cluster") } returns value
    every { dedup.claimCluster("cluster", 1, "state", any()) } returns true
    every { dedup.findRelationsForBooks(any()) } returns emptyList()
    every { dedup.updateClusterState(any(), any(), any(), any(), any(), any()) } returns true
    every { clusters.rebuildLibrary("library", any()) } returns 1
    every { clusters.currentFingerprints(any()) } returns ClusterFingerprints("topology", "evidence", "state")
    listOf("A", "B").forEach { id ->
      every { books.findByIdOrNull(id) } returns book(id)
      every { cover.currentSourceIdentity(id) } returns identity(id)
      every { localState.snapshot(id) } returns snapshots.getValue(id)
    }
    every { clusters.finalizeProcessed("cluster", any(), setOf("A", "B"), any()) } answers {
      value.cluster.copy(status = DedupClusterStatus.PROCESSED, processedRevision = 1, lastResolutionId = arg(1))
    }
    val lifecycle =
      DedupResolutionLifecycle(
        dedup,
        resolutions,
        books,
        suggestions,
        policy,
        localState,
        cover,
        deletion,
        clusters,
        gorse,
        jacksonObjectMapper().findAndRegisterModules(),
      )

    val result =
      lifecycle.createCustom(
        "cluster",
        1,
        "state",
        listOf(
          DedupCustomMemberSelection("A", DedupResolutionAction.KEEP),
          DedupCustomMemberSelection("B", DedupResolutionAction.KEEP),
        ),
        emptySet(),
        "admin",
      )

    assertThat(result.state).isEqualTo(DedupResolutionState.PROCESSED)
    assertThat(resolutions.findResolutionMembers(result.id)).allMatch { it.state == DedupResolutionMemberState.COMPLETED }
    verify(exactly = 0) { deletion.captureStrongIdentity(any(), any()) }
    verify(exactly = 0) { deletion.deleteVerifiedBook(any(), any()) }
    verify(exactly = 0) { gorse.syncNow(any(), any()) }
  }

  @Test
  fun `partial deletion records irreversible progress and retry never unlinks a successful member twice`() {
    val dedup = mockk<DedupRepository>()
    val resolutions = FakeResolutionRepository()
    val books = mockk<BookRepository>()
    val suggestions = mockk<DedupSuggestionPlanner>()
    val policy = mockk<DedupEligibilityPolicy>()
    val localState = mockk<DedupLocalStateLifecycle>()
    val cover = mockk<DedupCoverLifecycle>()
    val deletion = mockk<DedupPhysicalBookDeletionLifecycle>()
    val clusters = mockk<DedupClusterLifecycle>()
    val gorse = mockk<GorseDesiredStateLifecycle>()
    val value = cluster("A", "B", "C")
    val relations = listOf(relation("A", "B"), relation("A", "C"))
    val plan =
      DedupResolutionPlan(
        "plan",
        listOf(
          DedupPlanMember("A", DedupResolutionAction.KEEP),
          DedupPlanMember("B", DedupResolutionAction.DELETE, "A", relations[0].id),
          DedupPlanMember("C", DedupResolutionAction.DELETE, "A", relations[1].id),
        ),
      )
    val report = DedupEligibilityReport(true, true, true, true, 1, "state", "plan", LocalDateTime.now(), emptyList(), emptyList(), emptyList())
    val snapshots = listOf("A", "B", "C").associateWith { DedupLocalStateSnapshot(it, "local-$it", emptySet(), emptyMap()) }
    val paths = listOf("A", "B", "C").associateWith { Files.writeString(directory.resolve("$it.cbz"), "archive-$it") }
    val softDeleted = mutableSetOf<String>()
    var clusterStatus = DedupClusterStatus.UNPROCESSED
    var cAttempts = 0

    every { policy.validateCustom(any(), any(), any(), any(), any()) } returns plan
    every { suggestions.evaluate("cluster") } returns DedupSuggestion(plan, report, snapshots)
    every { dedup.findCluster("cluster") } answers { value.copy(cluster = value.cluster.copy(status = clusterStatus)) }
    every { dedup.claimCluster("cluster", 1, "state", any()) } answers {
      clusterStatus = DedupClusterStatus.PROCESSING
      true
    }
    every { dedup.findRelationsForBooks(any()) } returns relations
    every { dedup.findRelation(any(), any()) } answers {
      val ids = setOf(firstArg<String>(), secondArg<String>())
      relations.firstOrNull { setOf(it.bookLowId, it.bookHighId) == ids }
    }
    every { dedup.updateClusterState(any(), any(), any(), any(), any(), any()) } answers {
      clusterStatus = thirdArg()
      true
    }
    every { clusters.rebuildLibrary("library", any()) } returns 1
    every { clusters.currentFingerprints(any()) } returns ClusterFingerprints("topology", "evidence", "state")
    every { clusters.finalizeProcessed("cluster", any(), setOf("A"), any()) } answers {
      clusterStatus = DedupClusterStatus.PROCESSED
      value.cluster.copy(status = clusterStatus, processedRevision = 2, lastResolutionId = secondArg())
    }
    listOf("A", "B", "C").forEach { id ->
      every { books.findByIdOrNull(id) } answers {
        book(id, paths.getValue(id))
          .copy(seriesId = if (id == "A") "series-A" else "series-delete")
          .let { if (id in softDeleted) it.copy(deletedDate = LocalDateTime.now()) else it }
      }
      every { cover.currentSourceIdentity(id) } returns identity(id)
      every { localState.snapshot(id) } returns snapshots.getValue(id)
    }
    every { deletion.precheck(any()) } returns DedupFilePrecheck(DedupFilePrecheckStatus.AVAILABLE, "unused", 9, 9, LocalDateTime.MIN, LocalDateTime.MIN)
    every { deletion.captureStrongIdentity(any(), any()) } answers {
      val item = firstArg<Book>()
      DedupStrongFileIdentity(paths.getValue(item.id).toString(), Files.size(paths.getValue(item.id)), LocalDateTime.MIN, "hash-${item.id}")
    }
    every { deletion.deleteVerifiedBook(any(), any(), any()) } answers {
      val item = firstArg<Book>()
      if (item.id == "C" && cAttempts++ == 0) {
        DedupPhysicalDeletionResult(DedupDeletionResultCode.DELETE_FAILED, false, false, "transient unlink failure")
      } else {
        Files.deleteIfExists(paths.getValue(item.id))
        thirdArg<() -> Unit>().invoke()
        softDeleted += item.id
        DedupPhysicalDeletionResult(DedupDeletionResultCode.DELETED, true, true)
      }
    }
    every { deletion.confirmPathAbsentAndSoftDelete(match { it.id == "B" }) } returns
      DedupPhysicalDeletionResult(DedupDeletionResultCode.ALREADY_DELETED_BY_THIS_RESOLUTION, true, true)
    every { gorse.syncNow(any(), any()) } answers { GorseSyncNowResult(firstArg(), GorseSyncNowState.CONFIRMED, true, null) }
    val lifecycle = DedupResolutionLifecycle(dedup, resolutions, books, suggestions, policy, localState, cover, deletion, clusters, gorse, jacksonObjectMapper().findAndRegisterModules())

    val failure =
      catchThrowableOfType(
        {
          lifecycle.createCustom(
            "cluster",
            1,
            "state",
            plan.members.map { DedupCustomMemberSelection(it.bookId, it.action, it.keeperBookId) },
            emptySet(),
            "admin",
          )
        },
        DedupResolutionExecutionException::class.java,
      )

    assertThat(failure.partial).isTrue()
    assertThat(resolutions.findResolution(failure.resolutionId!!)!!.state).isEqualTo(DedupResolutionState.PARTIALLY_COMPLETED)
    assertThat(resolutions.findResolutionMembers(failure.resolutionId!!).single { it.bookId == "B" }.state).isEqualTo(DedupResolutionMemberState.KOMGA_SAVED)

    val result = lifecycle.retry(failure.resolutionId!!)

    assertThat(result.state).isEqualTo(DedupResolutionState.PROCESSED)
    verify(exactly = 1) { deletion.deleteVerifiedBook(match { it.id == "B" }, any(), any()) }
    verify(exactly = 2) { deletion.deleteVerifiedBook(match { it.id == "C" }, any(), any()) }
    verify(exactly = 1) { deletion.confirmPathAbsentAndSoftDelete(match { it.id == "B" }) }
    verify(exactly = 1) { gorse.syncNow("series-delete", "library") }
    verify(exactly = 3) { deletion.captureStrongIdentity(match { it.id == "A" }, any()) }
  }

  private fun cluster(vararg requestedIds: String): DedupClusterWithMembers {
    val ids = requestedIds.toList().ifEmpty { listOf("A", "B") }
    val now = LocalDateTime.now()
    val cluster = DedupCluster("cluster", "library", 1, DedupClusterStatus.UNPROCESSED, true, ids.first(), "topology", "evidence", "state", null, null, null, null, now, now, null)
    return DedupClusterWithMembers(cluster, ids.map { DedupClusterMember("cluster", it, true, "content-$it", "cover-$it", "metadata-$it", "scope-$it", now, now) })
  }

  private fun identity(id: String) = DedupSourceIdentity(id, "series-$id", "library", "content-$id", "cover-$id", "metadata-$id", "scope-$id", 10)

  private fun book(id: String) = Book(id, URL("file:/tmp/$id.cbz"), LocalDateTime.MIN, 10, id = id, seriesId = "series-$id", libraryId = "library")

  private fun book(
    id: String,
    path: Path,
  ) = Book(id, path.toUri().toURL(), LocalDateTime.MIN, runCatching { Files.size(path) }.getOrDefault(9), id = id, seriesId = "series-$id", libraryId = "library")

  private fun relation(
    left: String,
    right: String,
  ) = org.gotson.komga.domain.model.DedupRelation(
    "relation-$left-$right",
    "library",
    minOf(left, right),
    maxOf(left, right),
    "content-${minOf(left, right)}",
    "content-${maxOf(left, right)}",
    type = org.gotson.komga.domain.model.DedupRelationType.EXACT_FILE,
  )
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
    resolutions[resolutionId] =
      current.copy(
        state = state,
        resultJson = resultJson,
        completedDate = completedDate,
        leaseToken = leaseToken ?: current.leaseToken,
        leaseUntil = leaseUntil ?: current.leaseUntil,
        lastModifiedDate = now,
      )
    return true
  }

  override fun updateResolutionMember(
    resolutionId: String,
    bookId: String,
    expectedStates: Set<DedupResolutionMemberState>,
    state: DedupResolutionMemberState,
    expectedPath: String?,
    expectedSize: Long?,
    expectedMtime: LocalDateTime?,
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
        expectedMtime = expectedMtime ?: current.expectedMtime,
        expectedArchiveHash = expectedArchiveHash ?: current.expectedArchiveHash,
        resultCode = resultCode,
        resultJson = resultJson,
        lastError = lastError,
        lastModifiedDate = now,
      )
    return true
  }

  override fun releaseExpiredResolutionLeases(now: LocalDateTime) = 0
}
