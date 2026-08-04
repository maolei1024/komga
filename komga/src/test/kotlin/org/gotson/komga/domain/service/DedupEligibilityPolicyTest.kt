package org.gotson.komga.domain.service

import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class DedupEligibilityPolicyTest {
  private val repository = mockk<DedupRepository>()
  private val resolutions = mockk<DedupResolutionRepository>()
  private val cover = mockk<DedupCoverLifecycle>()
  private val localState = mockk<DedupLocalStateLifecycle>()
  private val clusters = mockk<DedupClusterLifecycle>()
  private val policy = DedupEligibilityPolicy(repository, resolutions, cover, localState, clusters)

  @BeforeEach
  fun setup() {
    every { repository.findCluster("cluster") } returns cluster()
    every { resolutions.hasActiveResolutionForBooks(any()) } returns false
    every { cover.currentSourceIdentity("A") } returns identity("A")
    every { cover.currentSourceIdentity("B") } returns identity("B")
    every { localState.snapshot(any()) } answers {
      org.gotson.komga.domain.model
        .DedupLocalStateSnapshot(firstArg(), "state", emptySet(), emptyMap())
    }
    every { clusters.currentFingerprints(any()) } returns ClusterFingerprints("topology", "evidence", "state")
  }

  @Test
  fun `custom keep-all is valid and requires no file or Gorse eligibility`() {
    every { repository.findRelationsForBooks(any()) } returns emptyList()

    val plan =
      policy.validateCustom(
        "cluster",
        1,
        "state",
        listOf(
          DedupCustomMemberSelection("A", DedupResolutionAction.KEEP),
          DedupCustomMemberSelection("B", DedupResolutionAction.KEEP),
        ),
        emptySet(),
      )

    assertThat(plan.keepCount).isEqualTo(2)
    assertThat(plan.deleteCount).isZero()
  }

  @Test
  fun `risky direct relation requires its exact acknowledgement code`() {
    val relation =
      DedupRelation(
        "relation",
        "library",
        "A",
        "B",
        "content-A",
        "content-B",
        type = DedupRelationType.ALT_EDITION,
        featureSchemaVersion = 1,
        classifierRuleVersion = DedupDeepVerificationLifecycle.CLASSIFIER_RULE_VERSION,
      )
    every { repository.findRelationsForBooks(any()) } returns listOf(relation)
    val selections =
      listOf(
        DedupCustomMemberSelection("A", DedupResolutionAction.KEEP),
        DedupCustomMemberSelection("B", DedupResolutionAction.DELETE, "A"),
      )

    assertThatThrownBy { policy.validateCustom("cluster", 1, "state", selections, emptySet()) }
      .isInstanceOf(DedupResolutionValidationException::class.java)
      .hasMessageContaining(DedupSuggestionPlanner.riskCode(relation))

    val plan = policy.validateCustom("cluster", 1, "state", selections, setOf(DedupSuggestionPlanner.riskCode(relation)))
    assertThat(plan.deleteCount).isEqualTo(1)
  }

  @Test
  fun `unrelated direct evidence can never authorize custom deletion`() {
    every { repository.findRelationsForBooks(any()) } returns
      listOf(
        DedupRelation(
          "relation",
          "library",
          "A",
          "B",
          "content-A",
          "content-B",
          type = DedupRelationType.UNRELATED,
          classifierRuleVersion = DedupDeepVerificationLifecycle.CLASSIFIER_RULE_VERSION,
        ),
      )

    assertThatThrownBy {
      policy.validateCustom(
        "cluster",
        1,
        "state",
        listOf(
          DedupCustomMemberSelection("A", DedupResolutionAction.KEEP),
          DedupCustomMemberSelection("B", DedupResolutionAction.DELETE, "A"),
        ),
        emptySet(),
      )
    }.isInstanceOf(DedupResolutionValidationException::class.java).hasMessageContaining("unrelated")
  }

  @Test
  fun `custom approval rejects topology or evidence changed before cluster rebuild`() {
    every { repository.findRelationsForBooks(any()) } returns emptyList()
    every { clusters.currentFingerprints(any()) } returns ClusterFingerprints("changed-topology", "evidence", "state")

    assertThatThrownBy {
      policy.validateCustom(
        "cluster",
        1,
        "state",
        listOf(
          DedupCustomMemberSelection("A", DedupResolutionAction.KEEP),
          DedupCustomMemberSelection("B", DedupResolutionAction.KEEP),
        ),
        emptySet(),
      )
    }.isInstanceOf(DedupResolutionValidationException::class.java).hasMessageContaining("topology or evidence changed")
  }

  private fun cluster(): DedupClusterWithMembers {
    val now = LocalDateTime.now()
    val cluster = DedupCluster("cluster", "library", 1, DedupClusterStatus.UNPROCESSED, true, "A", "topology", "evidence", "state", null, null, null, null, now, now, null)
    return DedupClusterWithMembers(cluster, listOf("A", "B").map { DedupClusterMember("cluster", it, true, "content-$it", "cover-$it", "metadata-$it", "scope-$it", now, now) })
  }

  private fun identity(id: String) = DedupSourceIdentity(id, "series-$id", "library", "content-$id", "cover-$id", "metadata-$id", "scope-$id", 10)
}
