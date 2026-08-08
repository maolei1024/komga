package org.gotson.komga.domain.service

import com.github.f4b6a3.tsid.TsidCreator
import org.gotson.komga.domain.model.DedupCluster
import org.gotson.komga.domain.model.DedupClusterMember
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupClusterWithMembers
import org.gotson.komga.domain.model.DedupEvidenceMaturity
import org.gotson.komga.domain.model.DedupPairDecision
import org.gotson.komga.domain.model.DedupRelation
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
) {
  companion object {
    const val RULE_VERSION = 4
  }

  @Transactional
  fun rebuildLibrary(
    libraryId: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Int {
    dedupRepository.findLibrarySettings(libraryId) ?: return 0
    dedupRepository.lockLibraryForClusterRebuild(libraryId)
    val identities = coverLifecycle.currentSourceIdentities(libraryId).associateBy { it.bookId }
    val reviewEdges = currentReviewRelations(libraryId, identities)
    val nodes = reviewEdges.flatMapTo(mutableSetOf()) { setOf(it.bookLowId, it.bookHighId) }
    val components = connectedComponents(nodes, reviewEdges).filter { it.size >= 2 }.sortedBy { it.first() }

    // Successful clusters are immutable history anchors. Only current unresolved projections
    // can be reused, merged, split, hidden, or superseded.
    val existing =
      dedupRepository
        .findAllClusters(libraryId)
        .filter { it.cluster.status != DedupClusterStatus.PROCESSED && it.cluster.supersededBy == null }
    val used = mutableSetOf<String>()

    components.forEach { memberIds ->
      val overlaps =
        existing
          .filter { old -> old.presentIds().any(memberIds::contains) }
          .sortedWith(compareBy({ it.cluster.createdDate }, { it.cluster.id }))
      val chosen = overlaps.firstOrNull()
      if (chosen?.cluster?.status == DedupClusterStatus.PROCESSING) {
        used += chosen.cluster.id
        return@forEach
      }
      val id = chosen?.cluster?.id ?: "cluster-${TsidCreator.getTsid256()}"
      val memberIdentities = memberIds.mapNotNull(identities::get)
      val componentEdges = reviewEdges.filter { it.bookLowId in memberIds && it.bookHighId in memberIds }
      val fingerprints = fingerprints(memberIdentities, componentEdges)
      val old = chosen?.cluster
      val changed = old == null || old.topologyFingerprint != fingerprints.topology || old.evidenceFingerprint != fingerprints.evidence
      val value =
        DedupCluster(
          id = id,
          libraryId = libraryId,
          revision = if (old == null) 1 else old.revision + if (changed) 1 else 0,
          status = old?.status?.takeIf { it == DedupClusterStatus.NEEDS_ATTENTION } ?: DedupClusterStatus.UNPROCESSED,
          reviewable = true,
          anchorBookId = old?.anchorBookId?.takeIf(memberIds::contains) ?: memberIds.first(),
          topologyFingerprint = fingerprints.topology,
          evidenceFingerprint = fingerprints.evidence,
          stateFingerprint = fingerprints.state,
          processedRevision = null,
          lastResolutionId = old?.lastResolutionId,
          reopenReason = null,
          supersededBy = null,
          createdDate = old?.createdDate ?: now,
          lastModifiedDate = if (changed) now else old.lastModifiedDate,
          processedDate = null,
          memberCount = memberIds.size,
          verifiedPairCount = componentEdges.size,
          totalPairCount = totalPairCount(memberIds.size),
          evidenceMaturity = evidenceMaturity(memberIds.size, componentEdges.size),
        )
      val oldMembers = chosen?.members.orEmpty().associateBy { it.bookId }
      dedupRepository.saveCluster(value, memberIdentities.map { it.toMember(id, oldMembers[it.bookId]?.createdDate ?: now, now) })
      used += id
      overlaps.drop(1).filter { it.cluster.status != DedupClusterStatus.PROCESSING }.forEach {
        dedupRepository.markClusterSuperseded(it.cluster.id, id, now)
        used += it.cluster.id
      }
    }

    existing.filterNot { it.cluster.id in used }.forEach { old ->
      if (old.cluster.status == DedupClusterStatus.PROCESSING) return@forEach
      val present = old.presentIds().mapNotNull(identities::get)
      val fingerprints = fingerprints(present, emptyList())
      val changed = old.cluster.reviewable || old.cluster.topologyFingerprint != fingerprints.topology || old.cluster.evidenceFingerprint != fingerprints.evidence
      dedupRepository.saveCluster(
        old.cluster.copy(
          revision = old.cluster.revision + if (changed) 1 else 0,
          reviewable = false,
          topologyFingerprint = fingerprints.topology,
          evidenceFingerprint = fingerprints.evidence,
          stateFingerprint = fingerprints.state,
          reopenReason = null,
          lastModifiedDate = if (changed) now else old.cluster.lastModifiedDate,
          memberCount = present.size,
          verifiedPairCount = 0,
          totalPairCount = totalPairCount(present.size),
          evidenceMaturity = DedupEvidenceMaturity.COVER_ONLY,
        ),
        present.map { identity -> identity.toMember(old.cluster.id, old.members.firstOrNull { it.bookId == identity.bookId }?.createdDate ?: now, now) },
      )
    }
    return components.size
  }

  @Transactional
  fun finalizeProcessed(
    clusterId: String,
    resolutionId: String,
    actorId: String,
    survivorBookIds: Set<String>,
    now: LocalDateTime = LocalDateTime.now(),
  ): DedupCluster {
    val current = requireNotNull(dedupRepository.findCluster(clusterId))
    check(current.cluster.status == DedupClusterStatus.PROCESSING) { "Cluster is not processing" }
    val identities = survivorBookIds.mapNotNull(coverLifecycle::currentSourceIdentity)
    check(identities.map { it.bookId }.toSet() == survivorBookIds) { "A retained Book changed before cluster finalization" }
    val reviewEdges = currentReviewRelationsForIdentities(identities.associateBy { it.bookId })
    dedupRepository.savePairDecisions(
      reviewEdges.map { relation ->
        DedupPairDecision(
          bookLowId = relation.bookLowId,
          bookHighId = relation.bookHighId,
          resolutionId = resolutionId,
          actorId = actorId,
          createdDate = now,
        )
      },
    )
    val fingerprints = fingerprints(identities, reviewEdges)
    val value =
      current.cluster.copy(
        status = DedupClusterStatus.PROCESSED,
        reviewable = false,
        topologyFingerprint = fingerprints.topology,
        evidenceFingerprint = fingerprints.evidence,
        stateFingerprint = fingerprints.state,
        processedRevision = current.cluster.revision,
        lastResolutionId = resolutionId,
        reopenReason = null,
        lastModifiedDate = now,
        processedDate = now,
        memberCount = survivorBookIds.size,
        verifiedPairCount = reviewEdges.size,
        totalPairCount = totalPairCount(survivorBookIds.size),
        evidenceMaturity = evidenceMaturity(survivorBookIds.size, reviewEdges.size),
      )
    val oldMembers = current.members.associateBy { it.bookId }
    dedupRepository.saveCluster(value, identities.map { it.toMember(clusterId, oldMembers[it.bookId]?.createdDate ?: now, now) })
    return value
  }

  fun currentFingerprints(value: DedupClusterWithMembers): ClusterFingerprints? {
    val ids = value.presentIds()
    val memberIdentities = ids.mapNotNull(coverLifecycle::currentSourceIdentity).associateBy { it.bookId }
    if (memberIdentities.size != ids.size || memberIdentities.values.any { it.libraryId != value.cluster.libraryId }) return null

    val candidates =
      dedupRepository
        .findRelationsTouchingBooks(value.cluster.libraryId, ids)
        .filter { it.type.reviewable }
    val externalIdentities =
      candidates
        .flatMapTo(mutableSetOf()) { setOf(it.bookLowId, it.bookHighId) }
        .minus(ids)
        .mapNotNull(coverLifecycle::currentSourceIdentity)
        .filter { it.libraryId == value.cluster.libraryId }
        .associateBy { it.bookId }
    val reviewRelations = currentReviewRelations(value.cluster.libraryId, memberIdentities + externalIdentities, candidates)
    if (reviewRelations.any { it.bookLowId !in ids || it.bookHighId !in ids }) return null
    return fingerprints(memberIdentities.values, reviewRelations)
  }

  fun currentReviewRelations(bookIds: Set<String>): List<DedupRelation> {
    if (bookIds.isEmpty()) return emptyList()
    val identities = bookIds.mapNotNull(coverLifecycle::currentSourceIdentity).associateBy { it.bookId }
    if (identities.size != bookIds.size) return emptyList()
    return currentReviewRelationsForIdentities(identities)
  }

  internal fun currentReviewRelationsForIdentities(identities: Map<String, DedupSourceIdentity>): List<DedupRelation> {
    if (identities.isEmpty()) return emptyList()
    val libraryIds = identities.values.mapTo(mutableSetOf()) { it.libraryId }
    if (libraryIds.size != 1) return emptyList()
    return currentReviewRelations(
      libraryId = libraryIds.single(),
      identities = identities,
      candidates = dedupRepository.findRelationsForBooks(identities.keys),
    )
  }

  private fun currentReviewRelations(
    libraryId: String,
    identities: Map<String, DedupSourceIdentity>,
    candidates: List<DedupRelation> = dedupRepository.findRelations(libraryId),
  ): List<DedupRelation> {
    val suppressed = dedupRepository.findPairDecisions(libraryId).map { it.bookLowId to it.bookHighId }.toSet()
    return candidates.filter { relation ->
      relation.type.reviewable &&
        relation.isCurrent(identities) &&
        (relation.bookLowId to relation.bookHighId) !in suppressed
    }
  }

  fun fingerprints(
    identities: Collection<DedupSourceIdentity>,
    relations: Collection<DedupRelation>,
  ): ClusterFingerprints {
    val members = identities.sortedBy { it.bookId }
    val edges = relations.sortedWith(compareBy({ it.bookLowId }, { it.bookHighId }))
    val topology = stableHash(members.joinToString("|") { it.bookId } + "#" + edges.joinToString("|") { "${it.bookLowId}:${it.bookHighId}" })
    val evidence =
      stableHash(
        members.joinToString("|") { "${it.bookId}:${it.contentGeneration}:${it.coverGeneration}:${it.metadataGeneration}" } + "#" +
          edges.joinToString("|") { "${it.id}:${it.type}:${it.lowContentGeneration}:${it.highContentGeneration}:${it.featureSchemaVersion}:${it.classifierRuleVersion}:${it.evidenceJson}" },
      )
    return ClusterFingerprints(topology, evidence, stableHash("dedup-v2:$RULE_VERSION:$topology:$evidence"))
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

  private fun DedupClusterWithMembers.presentIds(): Set<String> = members.filter { it.present }.map { it.bookId }.toSet()

  private fun totalPairCount(memberCount: Int): Int = memberCount * (memberCount - 1) / 2

  private fun evidenceMaturity(
    memberCount: Int,
    verifiedCount: Int,
  ): DedupEvidenceMaturity =
    when {
      verifiedCount == 0 -> DedupEvidenceMaturity.COVER_ONLY
      verifiedCount >= totalPairCount(memberCount) -> DedupEvidenceMaturity.COMPLETE
      else -> DedupEvidenceMaturity.PARTIAL
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
