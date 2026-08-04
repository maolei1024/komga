package org.gotson.komga.domain.service

import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupEligibilityReason
import org.gotson.komga.domain.model.DedupEligibilityReport
import org.gotson.komga.domain.model.DedupEligibilitySeverity
import org.gotson.komga.domain.model.DedupLocalStateSnapshot
import org.gotson.komga.domain.model.DedupPlanMember
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationStatus
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionPlan
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime

data class DedupSuggestion(
  val plan: DedupResolutionPlan?,
  val eligibility: DedupEligibilityReport,
  val localStates: Map<String, DedupLocalStateSnapshot>,
)

@Service
class DedupSuggestionPlanner(
  private val dedupRepository: DedupRepository,
  private val resolutionRepository: DedupResolutionRepository,
  private val bookRepository: BookRepository,
  private val coverLifecycle: DedupCoverLifecycle,
  private val localStateLifecycle: DedupLocalStateLifecycle,
  private val physicalDeletionLifecycle: DedupPhysicalBookDeletionLifecycle,
  private val clusterLifecycle: DedupClusterLifecycle,
) {
  fun evaluate(clusterId: String): DedupSuggestion = evaluate(requireNotNull(dedupRepository.findCluster(clusterId)))

  fun evaluate(value: DedupClusterWithMembers): DedupSuggestion {
    val present = value.members.filter { it.present }.sortedBy { it.bookId }
    val ids = present.map { it.bookId }.toSet()
    val identities = ids.mapNotNull(coverLifecycle::currentSourceIdentity).associateBy { it.bookId }
    val relations = dedupRepository.findRelationsForBooks(ids).filter { it.isCurrent(identities) }
    val localStates = identities.keys.associateWith(localStateLifecycle::snapshot)
    val available =
      identities.keys.associateWith { id ->
        coverLifecycle.currentSourceIdentity(id) != null &&
          runCatching {
            val book = requireNotNull(bookRepository.findByIdOrNull(id))
            physicalDeletionLifecycle.precheck(book).status == DedupFilePrecheckStatus.AVAILABLE
          }.getOrDefault(false)
      }
    val plan = buildPlan(ids, relations, localStates, available)
    val blockers = mutableListOf<DedupEligibilityReason>()
    if (value.cluster.status != DedupClusterStatus.UNPROCESSED || !value.cluster.reviewable) {
      blockers += reason("CLUSTER_NOT_UNPROCESSED", ids)
    }
    if (identities.size != ids.size ||
      present.any { member ->
        identities[member.bookId]?.let {
          it.contentGeneration != member.sourceContentGeneration || it.coverGeneration != member.sourceCoverGeneration ||
            it.metadataGeneration != member.sourceMetadataGeneration || it.seriesScopeRevision != member.seriesScopeRevision
        } != false
      }
    )
      blockers += reason("CLUSTER_REVISION_STALE", ids)
    if (resolutionRepository.hasActiveResolutionForBooks(ids)) blockers += reason("MEMBER_RESOLUTION_ACTIVE", ids)
    val currentFingerprints = clusterLifecycle.currentFingerprints(value)
    if (currentFingerprints == null || currentFingerprints.topology != value.cluster.topologyFingerprint || currentFingerprints.evidence != value.cluster.evidenceFingerprint) {
      blockers += reason("CLUSTER_REVISION_STALE", ids)
    }
    if (currentFingerprints?.state != value.cluster.stateFingerprint) blockers += reason("STATE_REVISION_CHANGED", ids)
    plan?.members?.filter { it.action == DedupResolutionAction.DELETE }?.forEach { member ->
      if (available[member.bookId] != true) blockers += reason("DELETE_FILE_UNAVAILABLE", setOf(member.bookId))
      if (localStates[member.bookId]?.reasonCodes?.isNotEmpty() == true) blockers += reason("LOCAL_STATE_WOULD_BE_DISCARDED", setOf(member.bookId))
    }
    val planAvailable = plan?.deleteCount?.let { it > 0 } == true
    val evidenceEligible =
      planAvailable &&
        plan!!.members.filter { it.action == DedupResolutionAction.DELETE }.all { member ->
          relations.any { it.id == member.directRelationId && it.isSafeFor(member.bookId, member.keeperBookId!!) }
        }
    val processingEligible = blockers.isEmpty()
    val warnings =
      relations.filter { it.type in RISK_TYPES }.map {
        DedupEligibilityReason(riskCode(it), DedupEligibilitySeverity.WARNING, setOf("CUSTOM"), true, "PAIR", setOf(it.bookLowId, it.bookHighId))
      } +
        localStates.values.flatMap { snapshot ->
          snapshot.reasonCodes.map { code ->
            DedupEligibilityReason(localStateRiskCode(snapshot.bookId, code), DedupEligibilitySeverity.WARNING, setOf("CUSTOM"), true, "MEMBER", setOf(snapshot.bookId))
          }
        }
    val report =
      DedupEligibilityReport(
        suggestionPlanAvailable = planAvailable,
        suggestionEvidenceEligible = evidenceEligible,
        processingEligible = processingEligible,
        suggestedPlanEligible = planAvailable && evidenceEligible && processingEligible,
        ruleVersion = DedupClusterLifecycle.ELIGIBILITY_RULE_VERSION,
        stateRevision = value.cluster.stateFingerprint,
        planRevision = plan?.revision,
        evaluatedAt = LocalDateTime.now(),
        blockers = blockers.distinctBy { it.code to it.memberIds },
        warnings = warnings,
        passed = if (blockers.isEmpty()) listOf(DedupEligibilityReason("PROCESSING_PRECHECK_PASSED", DedupEligibilitySeverity.PASSED, setOf("SUGGESTED", "CUSTOM"), false, "CLUSTER", ids)) else emptyList(),
      )
    return DedupSuggestion(plan, report, localStates)
  }

  private fun buildPlan(
    ids: Set<String>,
    relations: List<DedupRelation>,
    localStates: Map<String, DedupLocalStateSnapshot>,
    available: Map<String, Boolean>,
  ): DedupResolutionPlan? {
    val remaining = ids.toMutableSet()
    val keepers = linkedSetOf<String>()
    val assignments = linkedMapOf<String, Pair<String, DedupRelation>>()
    while (remaining.isNotEmpty()) {
      val coverage =
        remaining.associateWith { keeper ->
          remaining
            .filter { loser ->
              loser != keeper && loser !in keepers && localStates[loser]?.reasonCodes.orEmpty().isEmpty() &&
                relations.any { it.isSafeFor(loser, keeper) }
            }.toSet()
        }
      val best =
        coverage.entries
          .filter { it.value.isNotEmpty() }
          .sortedWith(
            compareByDescending<Map.Entry<String, Set<String>>> { it.value.size }
              .thenByDescending { localPriority(localStates.getValue(it.key)) }
              .thenByDescending { if (available[it.key] == true) 1 else 0 }
              .thenBy { it.key },
          ).firstOrNull()
      if (best == null) {
        keepers += remaining.sorted()
        break
      }
      val keeper = best.key
      keepers += keeper
      remaining -= keeper
      best.value.sorted().forEach { loser ->
        val direct = relations.first { it.isSafeFor(loser, keeper) }
        assignments[loser] = keeper to direct
        remaining -= loser
      }
    }
    val members =
      ids.sorted().map { id ->
        assignments[id]?.let { (keeper, direct) -> DedupPlanMember(id, DedupResolutionAction.DELETE, keeper, direct.id) }
          ?: DedupPlanMember(id, DedupResolutionAction.KEEP)
      }
    if (members.none { it.action == DedupResolutionAction.DELETE }) return null
    return DedupResolutionPlan(stableHash(canonicalPlan(members)), members)
  }

  private fun localPriority(snapshot: DedupLocalStateSnapshot): Int {
    val userState = snapshot.reasonCodes.count { it in setOf("READ_PROGRESS_PRESENT", "READLIST_PRESENT", "COLLECTION_PRESENT") }
    val custom = if ("USER_THUMBNAIL_OR_LOCKED_METADATA" in snapshot.reasonCodes) 1 else 0
    return userState * 10 + custom
  }

  private fun reason(
    code: String,
    ids: Set<String>,
  ) = DedupEligibilityReason(code, DedupEligibilitySeverity.BLOCKER, setOf("SUGGESTED", "CUSTOM"), false, "CLUSTER", ids)

  private fun canonicalPlan(members: List<DedupPlanMember>): String = members.sortedBy { it.bookId }.joinToString("|") { "${it.bookId}:${it.action}:${it.keeperBookId.orEmpty()}:${it.directRelationId.orEmpty()}" }

  private fun stableHash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

  companion object {
    val RISK_TYPES =
      setOf(
        DedupRelationType.VISUALLY_SIMILAR,
        DedupRelationType.SAME_EDITION_VARIANT,
        DedupRelationType.NEAR_CONTAINED_IN,
        DedupRelationType.PARTIAL_OVERLAP,
        DedupRelationType.ALT_EDITION,
        DedupRelationType.EDITION_UNCERTAIN,
      )

    fun riskCode(relation: DedupRelation): String = "RISK_${relation.type}_${relation.bookLowId}_${relation.bookHighId}"

    fun localStateRiskCode(
      bookId: String,
      reasonCode: String,
    ): String = "LOCAL_STATE_${reasonCode}_$bookId"
  }
}

internal fun DedupRelation.isCurrent(identities: Map<String, DedupSourceIdentity>): Boolean {
  val low = identities[bookLowId] ?: return false
  val high = identities[bookHighId] ?: return false
  if (status != DedupRelationStatus.VERIFIED || low.contentGeneration != lowContentGeneration || high.contentGeneration != highContentGeneration) return false
  if (type == DedupRelationType.EXACT_FILE) return true
  if (type == DedupRelationType.VISUALLY_SIMILAR) return low.coverGeneration == lowCoverGeneration && high.coverGeneration == highCoverGeneration
  return featureSchemaVersion == DedupDeepVerificationLifecycle.PAGE_FEATURE_SCHEMA_VERSION &&
    classifierRuleVersion == DedupDeepVerificationLifecycle.CLASSIFIER_RULE_VERSION
}

internal fun DedupRelation.isSafeFor(
  loser: String,
  keeper: String,
): Boolean =
  when (type) {
    DedupRelationType.EXACT_FILE, DedupRelationType.EXACT_PAGE_SEQUENCE -> setOf(loser, keeper) == setOf(bookLowId, bookHighId)
    DedupRelationType.CONTAINED_IN -> loser == containedBookId && keeper == containerBookId
    else -> false
  }
