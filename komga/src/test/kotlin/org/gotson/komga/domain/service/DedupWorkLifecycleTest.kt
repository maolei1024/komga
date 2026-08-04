package org.gotson.komga.domain.service

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupWork
import org.gotson.komga.domain.model.DedupWorkState
import org.gotson.komga.domain.model.DedupWorkType
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.gotson.komga.infrastructure.gorse.GorseDesiredStateLifecycle
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DedupWorkLifecycleTest {
  private val repository = mockk<DedupRepository>()
  private val resolutionRepository = mockk<DedupResolutionRepository>()
  private val exact = mockk<DedupExactDuplicateLifecycle>()
  private val cover = mockk<DedupCoverLifecycle>()
  private val deep = mockk<DedupDeepVerificationLifecycle>()
  private val clusters = mockk<DedupClusterLifecycle>()
  private val emitter = mockk<TaskEmitter>()
  private val gorse = mockk<GorseDesiredStateLifecycle>()
  private val lifecycle = DedupWorkLifecycle(repository, resolutionRepository, exact, cover, deep, clusters, emitter, gorse)

  @Test
  fun `cluster verification queues every unordered non-exact pair and wakes one Library once`() {
    val cluster = cluster("A", "B", "C")
    every { repository.findCluster("cluster") } returns cluster
    every { repository.findRelation("A", "B") } returns exact("A", "B")
    every { repository.findRelation("A", "C") } returns null
    every { repository.findRelation("B", "C") } returns null
    every { repository.enqueueWork(any(), "library", DedupWorkType.VERIFY_RELATION, any(), any(), 6, any()) } answers {
      work(id = firstArg(), target = arg(3))
    }
    every { emitter.drainDedupQueue("library", 6) } just Runs

    val results = lifecycle.requestClusterVerifications(listOf(DedupClusterVerificationRequest("cluster", 1)))

    assertThat(results.single().status).isEqualTo(DedupClusterVerificationStatus.QUEUED)
    assertThat(results.single().pairCount).isEqualTo(3)
    assertThat(results.single().queuedPairs).isEqualTo(2)
    assertThat(results.single().skippedPairs).isEqualTo(1)
    verify(exactly = 1) { repository.enqueueWork(any(), "library", DedupWorkType.VERIFY_RELATION, "A|C", any(), 6, any()) }
    verify(exactly = 1) { repository.enqueueWork(any(), "library", DedupWorkType.VERIFY_RELATION, "B|C", any(), 6, any()) }
    verify(exactly = 1) { emitter.drainDedupQueue("library", 6) }
  }

  @Test
  fun `stale cluster revision never queues pair work`() {
    every { repository.findCluster("cluster") } returns cluster("A", "B")

    val result = lifecycle.requestClusterVerification("cluster", 2)

    assertThat(result.status).isEqualTo(DedupClusterVerificationStatus.STALE)
    verify(exactly = 0) { repository.enqueueWork(any(), any(), any(), any(), any(), any(), any()) }
  }

  private fun cluster(vararg ids: String): DedupClusterWithMembers {
    val now = LocalDateTime.now()
    val value = DedupCluster("cluster", "library", 1, DedupClusterStatus.UNPROCESSED, true, ids.first(), "topology", "evidence", "state", null, null, null, null, now, now, null)
    return DedupClusterWithMembers(value, ids.map { DedupClusterMember("cluster", it, true, "content-$it", "cover-$it", "metadata-$it", "scope-$it", now, now) })
  }

  private fun exact(
    left: String,
    right: String,
  ) = DedupRelation("relation", "library", left, right, "content-$left", "content-$right", type = DedupRelationType.EXACT_FILE)

  private fun work(
    id: String,
    target: String,
  ): DedupWork {
    val now = LocalDateTime.now()
    return DedupWork(
      id,
      "library",
      DedupWorkType.VERIFY_RELATION,
      target,
      DedupWorkState.WAITING,
      1,
      0,
      now,
      null,
      null,
      null,
      null,
      0,
      8,
      null,
      null,
      6,
      now,
      now,
      null,
    )
  }
}
