package org.gotson.komga.domain.service

import com.github.f4b6a3.tsid.TsidCreator
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupEvidenceMaturity
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationStatus
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.DedupRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.SortedSet

@Service
class DedupClusterLifecycle(
  private val dedupRepository: DedupRepository,
  private val coverLifecycle: DedupCoverLifecycle,
  private val localStateLifecycle: DedupLocalStateLifecycle,
) {
  companion object {
    const val ELIGIBILITY_RULE_VERSION = 2
  }

  @Transactional
  fun rebuildLibrary(
    libraryId: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Int {
    val settings = dedupRepository.findLibrarySettings(libraryId) ?: return 0
    dedupRepository.lockLibraryForClusterRebuild(libraryId)
    val identities = coverLifecycle.currentSourceIdentities(libraryId).associateBy { it.bookId }
    val allRelations = dedupRepository.findRelations(libraryId)
    val currentRelations = allRelations.filter { it.isCurrent(identities) }
    val edges =
      currentRelations.filter { relation ->
        relation.status !in setOf(DedupRelationStatus.STALE, DedupRelationStatus.REJECTED, DedupRelationStatus.FAILED_REVIEW) &&
          (
            relation.type == DedupRelationType.EXACT_FILE ||
              (relation.coverDistance != null && relation.coverDistance <= settings.coverCandidateDistance && relation.hasCurrentCover(identities))
          )
      }
    val components = connectedComponents(identities.keys, edges).filter { it.size >= 2 }.sortedBy { it.first() }
    val existing = dedupRepository.findAllClusters(libraryId).filter { it.cluster.supersededBy == null }
    val overlaps =
      existing.associateWith { old ->
        components.mapIndexedNotNull { index, ids ->
          index.takeIf {
            ids.any(
              old.members
                .filter { it.present }
                .map { member -> member.bookId }
                .toSet()::contains,
            )
          }
        }
      }
    val splitReuse =
      overlaps
        .mapNotNull { (old, indexes) ->
          if (indexes.size <= 1) return@mapNotNull null
          val selected = indexes.firstOrNull { old.cluster.anchorBookId in components[it] } ?: indexes.minBy { components[it].first() }
          old.cluster.id to selected
        }.toMap()

    val usedClusters = mutableSetOf<String>()
    components.forEachIndexed { index, memberIds ->
      val reusable =
        existing
          .filter { old ->
            val present =
              old.members
                .filter { it.present }
                .map { it.bookId }
                .toSet()
            memberIds.any(present::contains) && (splitReuse[old.cluster.id] == null || splitReuse[old.cluster.id] == index)
          }.sortedWith(compareBy({ it.cluster.createdDate }, { it.cluster.id }))
      val chosen = reusable.firstOrNull()
      val merged = reusable.drop(1)
      val split =
        existing.any {
          splitReuse[it.cluster.id] != null &&
            memberIds.any(
              it.members
                .filter { member -> member.present }
                .map { member -> member.bookId }
                .toSet()::contains,
            )
        }
      val id = chosen?.cluster?.id ?: "cluster-${TsidCreator.getTsid256()}"
      val memberIdentities = memberIds.mapNotNull(identities::get)
      val direct = currentRelations.filter { it.bookLowId in memberIds && it.bookHighId in memberIds }
      val componentEdges = edges.filter { it.bookLowId in memberIds && it.bookHighId in memberIds }
      val fingerprints = fingerprints(memberIdentities, direct, componentEdges)
      val old = chosen?.cluster
      val changed = old == null || old.topologyFingerprint != fingerprints.topology || old.evidenceFingerprint != fingerprints.evidence || old.stateFingerprint != fingerprints.state
      if (old?.status == DedupClusterStatus.PROCESSING) return@forEachIndexed
      val reason =
        when {
          merged.isNotEmpty() -> "CLUSTERS_MERGED"
          split -> "CLUSTER_SPLIT"
          old == null -> null
          old.topologyFingerprint != fingerprints.topology -> "TOPOLOGY_CHANGED"
          old.evidenceFingerprint != fingerprints.evidence -> "EVIDENCE_CHANGED"
          old.stateFingerprint != fingerprints.state -> "LOCAL_STATE_CHANGED"
          else -> old.reopenReason
        }
      val status =
        when {
          old == null -> DedupClusterStatus.UNPROCESSED
          merged.isNotEmpty() || split -> DedupClusterStatus.UNPROCESSED
          old.status == DedupClusterStatus.PROCESSED && changed -> DedupClusterStatus.UNPROCESSED
          else -> old.status
        }
      val value =
        DedupCluster(
          id = id,
          libraryId = libraryId,
          revision = if (old == null) 1 else old.revision + if (changed) 1 else 0,
          status = status,
          reviewable = true,
          anchorBookId = old?.anchorBookId?.takeIf(memberIds::contains) ?: memberIds.first(),
          topologyFingerprint = fingerprints.topology,
          evidenceFingerprint = fingerprints.evidence,
          stateFingerprint = fingerprints.state,
          processedRevision = if (status == DedupClusterStatus.PROCESSED) old?.processedRevision else null,
          lastResolutionId = old?.lastResolutionId,
          reopenReason = if (status == DedupClusterStatus.UNPROCESSED && changed) reason else old?.reopenReason,
          supersededBy = null,
          createdDate = old?.createdDate ?: now,
          lastModifiedDate = if (changed) now else old.lastModifiedDate,
          processedDate = if (status == DedupClusterStatus.PROCESSED) old?.processedDate else null,
          memberCount = memberIds.size,
          verifiedPairCount = verifiedPairCount(direct),
          totalPairCount = totalPairCount(memberIds.size),
          evidenceMaturity = evidenceMaturity(memberIds.size, direct),
        )
      val oldMembers = chosen?.members.orEmpty().associateBy { it.bookId }
      val members = memberIdentities.map { identity -> identity.toMember(id, oldMembers[identity.bookId]?.createdDate ?: now, now) }
      dedupRepository.saveCluster(value, members)
      usedClusters += id
      merged.forEach {
        dedupRepository.markClusterSuperseded(it.cluster.id, id, now)
        usedClusters += it.cluster.id
      }
    }

    existing.filterNot { it.cluster.id in usedClusters }.forEach { old ->
      if (old.cluster.status == DedupClusterStatus.PROCESSING) return@forEach
      val presentIds =
        old.members
          .filter { it.present && identities.containsKey(it.bookId) }
          .map { it.bookId }
          .toSortedSet()
      val memberIdentities = presentIds.mapNotNull(identities::get)
      val direct = currentRelations.filter { it.bookLowId in presentIds && it.bookHighId in presentIds }
      val fingerprints = fingerprints(memberIdentities, direct, emptyList())
      val changed = old.cluster.topologyFingerprint != fingerprints.topology || old.cluster.evidenceFingerprint != fingerprints.evidence || old.cluster.stateFingerprint != fingerprints.state
      val status = if (old.cluster.status == DedupClusterStatus.PROCESSED && changed) DedupClusterStatus.UNPROCESSED else old.cluster.status
      val value =
        old.cluster.copy(
          revision = old.cluster.revision + if (changed) 1 else 0,
          status = status,
          reviewable = false,
          topologyFingerprint = fingerprints.topology,
          evidenceFingerprint = fingerprints.evidence,
          stateFingerprint = fingerprints.state,
          processedRevision = if (status == DedupClusterStatus.PROCESSED) old.cluster.processedRevision else null,
          reopenReason = if (changed) "TOPOLOGY_CHANGED" else old.cluster.reopenReason,
          lastModifiedDate = if (changed || old.cluster.reviewable) now else old.cluster.lastModifiedDate,
          processedDate = if (status == DedupClusterStatus.PROCESSED) old.cluster.processedDate else null,
          memberCount = presentIds.size,
          verifiedPairCount = verifiedPairCount(direct),
          totalPairCount = totalPairCount(presentIds.size),
          evidenceMaturity = evidenceMaturity(presentIds.size, direct),
        )
      val oldMembers = old.members.associateBy { it.bookId }
      dedupRepository.saveCluster(value, memberIdentities.map { it.toMember(value.id, oldMembers[it.bookId]?.createdDate ?: now, now) })
    }
    return components.size
  }

  @Transactional
  fun finalizeProcessed(
    clusterId: String,
    resolutionId: String,
    survivorBookIds: Set<String>,
    now: LocalDateTime = LocalDateTime.now(),
  ): DedupCluster {
    val current = requireNotNull(dedupRepository.findCluster(clusterId))
    check(current.cluster.status == DedupClusterStatus.PROCESSING) { "Cluster is not processing" }
    val identities = survivorBookIds.mapNotNull(coverLifecycle::currentSourceIdentity)
    check(identities.map { it.bookId }.toSet() == survivorBookIds) { "A keeper changed before cluster finalization" }
    val relations = dedupRepository.findRelationsForBooks(survivorBookIds).filter { it.isCurrent(identities.associateBy { identity -> identity.bookId }) }
    val threshold = dedupRepository.findLibrarySettings(current.cluster.libraryId)?.coverCandidateDistance ?: 15
    val candidateRelations = relations.filter { it.type == DedupRelationType.EXACT_FILE || (it.coverDistance != null && it.coverDistance <= threshold) }
    val fingerprints = fingerprints(identities, relations, candidateRelations)
    val changed = current.cluster.topologyFingerprint != fingerprints.topology || current.cluster.evidenceFingerprint != fingerprints.evidence || current.cluster.stateFingerprint != fingerprints.state
    val revision = current.cluster.revision + if (changed) 1 else 0
    val value =
      current.cluster.copy(
        revision = revision,
        status = DedupClusterStatus.PROCESSED,
        reviewable = survivorBookIds.size >= 2,
        topologyFingerprint = fingerprints.topology,
        evidenceFingerprint = fingerprints.evidence,
        stateFingerprint = fingerprints.state,
        processedRevision = revision,
        lastResolutionId = resolutionId,
        reopenReason = null,
        lastModifiedDate = now,
        processedDate = now,
        memberCount = survivorBookIds.size,
        verifiedPairCount = verifiedPairCount(relations),
        totalPairCount = totalPairCount(survivorBookIds.size),
        evidenceMaturity = evidenceMaturity(survivorBookIds.size, relations),
      )
    val oldMembers = current.members.associateBy { it.bookId }
    dedupRepository.saveCluster(value, identities.map { it.toMember(clusterId, oldMembers[it.bookId]?.createdDate ?: now, now) })
    return value
  }

  fun currentFingerprints(value: org.gotson.komga.domain.model.DedupClusterWithMembers): ClusterFingerprints? {
    val ids =
      value.members
        .filter { it.present }
        .map { it.bookId }
        .toSet()
    val identities = ids.mapNotNull(coverLifecycle::currentSourceIdentity).associateBy { it.bookId }
    if (identities.size != ids.size) return null
    val settings = dedupRepository.findLibrarySettings(value.cluster.libraryId) ?: return null
    val relations = dedupRepository.findRelationsForBooks(ids).filter { it.isCurrent(identities) }
    val candidates =
      relations.filter { relation ->
        relation.status !in setOf(DedupRelationStatus.STALE, DedupRelationStatus.REJECTED, DedupRelationStatus.FAILED_REVIEW) &&
          (
            relation.type == DedupRelationType.EXACT_FILE ||
              (relation.coverDistance != null && relation.coverDistance <= settings.coverCandidateDistance && relation.hasCurrentCover(identities))
          )
      }
    return fingerprints(identities.values, relations, candidates)
  }

  fun fingerprints(
    identities: Collection<DedupSourceIdentity>,
    relations: Collection<DedupRelation>,
    candidateRelations: Collection<DedupRelation> = relations.filter { it.type == DedupRelationType.EXACT_FILE || it.coverDistance != null },
  ): ClusterFingerprints {
    val members = identities.sortedBy { it.bookId }
    val memberIds = members.map { it.bookId }.toSet()
    val currentRelations = relations.filter { it.bookLowId in memberIds && it.bookHighId in memberIds }.sortedWith(compareBy({ it.bookLowId }, { it.bookHighId }))
    val topology =
      stableHash(
        members.joinToString("|") { it.bookId } + "#" +
          candidateRelations
            .sortedWith(compareBy({ it.bookLowId }, { it.bookHighId }))
            .joinToString("|") { "${it.bookLowId}:${it.bookHighId}:${it.coverDistance ?: "EXACT"}" },
      )
    val evidence =
      stableHash(
        members.joinToString("|") { "${it.bookId}:${it.contentGeneration}:${it.coverGeneration}:${it.metadataGeneration}:${it.seriesScopeRevision}" } + "#" +
          currentRelations.joinToString("|") {
            "${it.id}:${it.type}:${it.status}:${it.lowContentGeneration}:${it.highContentGeneration}:${it.lowCoverGeneration}:${it.highCoverGeneration}:${it.featureSchemaVersion}:${it.classifierRuleVersion}:${it.evidenceJson}"
          },
      )
    val state =
      stableHash(
        members.joinToString("|") { identity -> "${identity.bookId}:${localStateLifecycle.snapshot(identity.bookId).revision}" } +
          "#eligibility:$ELIGIBILITY_RULE_VERSION",
      )
    return ClusterFingerprints(topology, evidence, state)
  }

  private fun connectedComponents(
    nodes: Set<String>,
    edges: Collection<DedupRelation>,
  ): List<SortedSet<String>> {
    val parent = nodes.associateWith { it }.toMutableMap()

    fun root(value: String): String {
      var current = value
      while (parent[current] != current) current = parent.getValue(current)
      var path = value
      while (parent[path] != current) {
        val next = parent.getValue(path)
        parent[path] = current
        path = next
      }
      return current
    }
    edges.forEach { edge ->
      val left = root(edge.bookLowId)
      val right = root(edge.bookHighId)
      if (left != right) parent[maxOf(left, right)] = minOf(left, right)
    }
    return nodes.groupBy(::root).values.map { it.toSortedSet() }
  }

  private fun DedupRelation.isCurrent(identities: Map<String, DedupSourceIdentity>): Boolean {
    val low = identities[bookLowId] ?: return false
    val high = identities[bookHighId] ?: return false
    return low.contentGeneration == lowContentGeneration && high.contentGeneration == highContentGeneration
  }

  private fun DedupRelation.hasCurrentCover(identities: Map<String, DedupSourceIdentity>): Boolean {
    val low = identities[bookLowId] ?: return false
    val high = identities[bookHighId] ?: return false
    return low.coverGeneration == lowCoverGeneration && high.coverGeneration == highCoverGeneration
  }

  private fun verifiedPairCount(relations: Collection<DedupRelation>): Int = relations.count { it.status == DedupRelationStatus.VERIFIED && it.type != DedupRelationType.VISUALLY_SIMILAR }

  private fun totalPairCount(memberCount: Int): Int = memberCount * (memberCount - 1) / 2

  private fun evidenceMaturity(
    memberCount: Int,
    relations: Collection<DedupRelation>,
  ): DedupEvidenceMaturity {
    val verified = verifiedPairCount(relations)
    val total = totalPairCount(memberCount)
    return when {
      verified == 0 -> DedupEvidenceMaturity.COVER_ONLY
      verified >= total -> DedupEvidenceMaturity.COMPLETE
      else -> DedupEvidenceMaturity.PARTIAL
    }
  }

  private fun DedupSourceIdentity.toMember(
    clusterId: String,
    created: LocalDateTime,
    modified: LocalDateTime,
  ) = DedupClusterMember(clusterId, bookId, true, contentGeneration, coverGeneration, metadataGeneration, seriesScopeRevision, created, modified)

  private fun stableHash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

data class ClusterFingerprints(
  val topology: String,
  val evidence: String,
  val state: String,
)
