package org.gotson.komga.domain.service

import org.gotson.komga.domain.model.DedupPlanMember
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionPlan
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class DedupResolutionValidationException(
  val code: String,
  message: String,
) : IllegalStateException(message)

data class DedupCustomMemberSelection(
  val bookId: String,
  val action: DedupResolutionAction,
  val keeperBookId: String? = null,
)

@Service
class DedupEligibilityPolicy(
  private val dedupRepository: DedupRepository,
  private val resolutionRepository: DedupResolutionRepository,
  private val coverLifecycle: DedupCoverLifecycle,
  private val localStateLifecycle: DedupLocalStateLifecycle,
  private val clusterLifecycle: DedupClusterLifecycle,
) {
  fun validateCustom(
    clusterId: String,
    expectedRevision: Long,
    stateRevision: String,
    selections: List<DedupCustomMemberSelection>,
    acknowledgedReasonCodes: Set<String>,
  ): DedupResolutionPlan {
    val value = dedupRepository.findCluster(clusterId) ?: conflict("CLUSTER_NOT_FOUND", "Cluster was not found")
    if (value.cluster.revision != expectedRevision || value.cluster.stateFingerprint != stateRevision) conflict("CLUSTER_STALE", "Cluster revision changed")
    val presentIds =
      value.members
        .filter { it.present }
        .map { it.bookId }
        .toSet()
    if (selections.map { it.bookId }.toSet().size != selections.size) conflict("DUPLICATE_MEMBER", "A Book appears more than once")
    if (selections.map { it.bookId }.toSet() != presentIds) conflict("INCOMPLETE_PLAN", "The plan must cover every present member")
    val keepers = selections.filter { it.action == DedupResolutionAction.KEEP }.map { it.bookId }.toSet()
    if (keepers.isEmpty()) conflict("KEEP_REQUIRED", "At least one Book must be kept")
    if (acknowledgedReasonCodes.size != acknowledgedReasonCodes.toList().distinct().size) conflict("DUPLICATE_REASON_CODE", "Reason codes must be unique")
    if (resolutionRepository.hasActiveResolutionForBooks(presentIds)) conflict("MEMBER_RESOLUTION_ACTIVE", "A member is already being processed")
    val identities = presentIds.mapNotNull(coverLifecycle::currentSourceIdentity).associateBy { it.bookId }
    if (identities.size != presentIds.size) conflict("MEMBER_STALE", "A member is no longer active and in scope")
    val relations = dedupRepository.findRelationsForBooks(presentIds).filter { it.isCurrent(identities) }
    val fingerprints = clusterLifecycle.currentFingerprints(value) ?: conflict("CLUSTER_STALE", "Cluster source state is unavailable")
    if (fingerprints.topology != value.cluster.topologyFingerprint || fingerprints.evidence != value.cluster.evidenceFingerprint) {
      conflict("CLUSTER_STALE", "Cluster topology or evidence changed")
    }
    if (fingerprints.state != value.cluster.stateFingerprint) conflict("STATE_REVISION_CHANGED", "Cluster processing state changed")
    val planned =
      selections.sortedBy { it.bookId }.map { selection ->
        if (selection.action == DedupResolutionAction.KEEP) {
          if (selection.keeperBookId != null) conflict("KEEPER_ON_KEEP", "KEEP members cannot name a keeper")
          DedupPlanMember(selection.bookId, selection.action)
        } else {
          val keeper = selection.keeperBookId ?: conflict("KEEPER_REQUIRED", "DELETE members must name a keeper")
          if (keeper !in keepers) conflict("INVALID_KEEPER", "A DELETE keeper must be a KEEP member")
          val relation =
            relations.firstOrNull { setOf(it.bookLowId, it.bookHighId) == setOf(selection.bookId, keeper) }
              ?: conflict("DIRECT_RELATION_REQUIRED", "Every DELETE requires a current direct relation")
          if (relation.type == DedupRelationType.UNRELATED) conflict("UNRELATED_DELETE_FORBIDDEN", "A relation classified as unrelated cannot be deleted")
          if (relation.type == DedupRelationType.CONTAINED_IN && !relation.isSafeFor(selection.bookId, keeper)) {
            conflict("CONTAINMENT_DIRECTION_INVALID", "A container cannot be deleted in favor of its contained Book")
          }
          if (relation.type in DedupSuggestionPlanner.RISK_TYPES && DedupSuggestionPlanner.riskCode(relation) !in acknowledgedReasonCodes) {
            conflict("RISK_CONFIRMATION_REQUIRED", DedupSuggestionPlanner.riskCode(relation))
          }
          localStateLifecycle.snapshot(selection.bookId).reasonCodes.forEach { reasonCode ->
            val code = DedupSuggestionPlanner.localStateRiskCode(selection.bookId, reasonCode)
            if (code !in acknowledgedReasonCodes) conflict("LOCAL_STATE_CONFIRMATION_REQUIRED", code)
          }
          DedupPlanMember(selection.bookId, selection.action, keeper, relation.id)
        }
      }
    val canonical = planned.joinToString("|") { "${it.bookId}:${it.action}:${it.keeperBookId.orEmpty()}:${it.directRelationId.orEmpty()}" }
    return DedupResolutionPlan(stableHash(canonical), planned)
  }

  private fun conflict(
    code: String,
    message: String,
  ): Nothing = throw DedupResolutionValidationException(code, message)

  private fun stableHash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
