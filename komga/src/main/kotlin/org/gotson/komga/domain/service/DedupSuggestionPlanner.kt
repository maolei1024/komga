package org.gotson.komga.domain.service

import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupPlanMember
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationStatus
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionPlan
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class DedupSuggestion(
  val plan: DedupResolutionPlan?,
)

@Service
class DedupSuggestionPlanner(
  private val dedupRepository: DedupRepository,
  private val bookRepository: BookRepository,
  private val coverLifecycle: DedupCoverLifecycle,
  private val clusterLifecycle: DedupClusterLifecycle,
) {
  fun evaluate(clusterId: String): DedupSuggestion = evaluate(requireNotNull(dedupRepository.findCluster(clusterId)))

  fun evaluate(value: DedupClusterWithMembers): DedupSuggestion {
    val ids =
      value.members
        .filter { it.present }
        .map { it.bookId }
        .toSet()
    if (ids.size < 2) return DedupSuggestion(null)
    val identities = ids.mapNotNull(coverLifecycle::currentSourceIdentity).associateBy { it.bookId }
    if (identities.size != ids.size) return DedupSuggestion(null)
    val relations = clusterLifecycle.currentReviewRelationsForIdentities(identities)
    val byPair = relations.associateBy { it.bookLowId to it.bookHighId }
    val possibleKeepers =
      ids.filter { keeper ->
        ids.filter { it != keeper }.all { deletion ->
          val pair = canonicalPair(keeper, deletion)
          byPair[pair]?.allowsSuggestedDeletion(deletion, keeper) == true
        }
      }
    if (possibleKeepers.isEmpty()) return DedupSuggestion(null)
    val keeper = uniqueKeeper(possibleKeepers, identities) ?: return DedupSuggestion(null)
    val members =
      ids.sorted().map { id ->
        if (id == keeper) {
          DedupPlanMember(id, DedupResolutionAction.KEEP)
        } else {
          val relation = requireNotNull(byPair[canonicalPair(id, keeper)])
          DedupPlanMember(id, DedupResolutionAction.DELETE, keeper, relation.id)
        }
      }
    return DedupSuggestion(DedupResolutionPlan(stableHash(canonicalPlan(members)), members))
  }

  private fun uniqueKeeper(
    candidates: List<String>,
    identities: Map<String, DedupSourceIdentity>,
  ): String? {
    if (candidates.size == 1) return candidates.single()
    val scores = candidates.associateWith { qualityScore(it, identities.getValue(it)) }
    val bestScore = scores.values.maxOrNull() ?: return null
    return scores.filterValues { it == bestScore }.keys.singleOrNull()
  }

  private fun qualityScore(
    bookId: String,
    identity: DedupSourceIdentity,
  ): QualityScore {
    val book = bookRepository.findByIdOrNull(bookId)
    val pageCount = identity.pageCount?.takeIf { it > 0 }
    val bytesPerPage = if (book != null && pageCount != null) book.fileSize.toDouble() / pageCount else 0.0
    val averagePageQuality =
      dedupRepository
        .findPageFeatures(bookId, identity.contentGeneration, DedupDeepVerificationLifecycle.PAGE_FEATURE_SCHEMA_VERSION)
        .mapNotNull { it.quality }
        .average()
        .takeUnless(Double::isNaN) ?: 0.0
    return QualityScore(bytesPerPage, book?.fileSize ?: 0, averagePageQuality)
  }

  private fun DedupRelation.allowsSuggestedDeletion(
    deletion: String,
    keeper: String,
  ): Boolean =
    when (type) {
      DedupRelationType.EXACT_FILE, DedupRelationType.EXACT_PAGE_SEQUENCE -> setOf(deletion, keeper) == setOf(bookLowId, bookHighId)
      DedupRelationType.CONTAINED_IN -> deletion == containedBookId && keeper == containerBookId
      else -> false
    }

  private fun canonicalPlan(members: List<DedupPlanMember>): String = members.sortedBy { it.bookId }.joinToString("|") { "${it.bookId}:${it.action}:${it.keeperBookId.orEmpty()}:${it.directRelationId.orEmpty()}" }

  private fun canonicalPair(
    first: String,
    second: String,
  ): Pair<String, String> = if (first < second) first to second else second to first

  private fun stableHash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

  private data class QualityScore(
    val bytesPerPage: Double,
    val fileSize: Long,
    val averagePageQuality: Double,
  ) : Comparable<QualityScore> {
    override fun compareTo(other: QualityScore): Int = compareValuesBy(this, other, QualityScore::bytesPerPage, QualityScore::fileSize, QualityScore::averagePageQuality)
  }
}

internal fun DedupRelation.isCurrent(identities: Map<String, DedupSourceIdentity>): Boolean {
  val low = identities[bookLowId] ?: return false
  val high = identities[bookHighId] ?: return false
  if (status != DedupRelationStatus.VERIFIED || low.contentGeneration != lowContentGeneration || high.contentGeneration != highContentGeneration) return false
  if (type == DedupRelationType.EXACT_FILE) return true
  if (
    low.coverGeneration != lowCoverGeneration || high.coverGeneration != highCoverGeneration ||
    low.metadataGeneration != lowMetadataGeneration || high.metadataGeneration != highMetadataGeneration
  )
    return false
  return featureSchemaVersion == DedupDeepVerificationLifecycle.PAGE_FEATURE_SCHEMA_VERSION &&
    classifierRuleVersion == DedupDeepVerificationLifecycle.CLASSIFIER_RULE_VERSION
}
