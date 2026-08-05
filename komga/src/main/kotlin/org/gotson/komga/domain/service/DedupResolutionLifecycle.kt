package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import org.gotson.komga.domain.model.DedupArchiveHashState
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionMember
import org.gotson.komga.domain.model.DedupResolutionMemberState
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.model.DedupResolutionPlan
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.gotson.komga.infrastructure.gorse.GorseDesiredStateLifecycle
import org.gotson.komga.infrastructure.gorse.GorseSyncNowState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime

class DedupResolutionExecutionException(
  val resolutionId: String?,
  val code: String,
  val partial: Boolean,
  message: String,
) : IllegalStateException(message)

@Service
class DedupResolutionLifecycle(
  private val dedupRepository: DedupRepository,
  private val resolutionRepository: DedupResolutionRepository,
  private val bookRepository: BookRepository,
  private val suggestionPlanner: DedupSuggestionPlanner,
  private val eligibilityPolicy: DedupEligibilityPolicy,
  private val localStateLifecycle: DedupLocalStateLifecycle,
  private val coverLifecycle: DedupCoverLifecycle,
  private val physicalDeletionLifecycle: DedupPhysicalBookDeletionLifecycle,
  private val clusterLifecycle: DedupClusterLifecycle,
  private val gorseDesiredStateLifecycle: GorseDesiredStateLifecycle,
  private val objectMapper: ObjectMapper,
) {
  private val leaseDuration = Duration.ofMinutes(30)

  fun createSuggested(
    clusterId: String,
    expectedRevision: Long,
    stateRevision: String,
    planRevision: String,
    actorId: String,
  ): DedupResolution {
    val value = refreshClusterForSubmission(clusterId)
    if (value.cluster.revision != expectedRevision || value.cluster.stateFingerprint != stateRevision) validation("CLUSTER_STALE", "Cluster revision changed")
    val suggestion = suggestionPlanner.evaluate(value)
    val plan = suggestion.plan ?: validation("SUGGESTION_UNAVAILABLE", "No safe suggested deletion is available")
    if (!suggestion.eligibility.suggestedPlanEligible) validation("SUGGESTION_INELIGIBLE", "Suggested processing requirements are not satisfied")
    if (plan.revision != planRevision) validation("PLAN_STALE", "Suggested plan revision changed")
    return executeNew(value.cluster.id, expectedRevision, stateRevision, DedupResolutionMode.SUGGESTED, plan, suggestion, actorId)
  }

  fun createCustom(
    clusterId: String,
    expectedRevision: Long,
    stateRevision: String,
    selections: List<DedupCustomMemberSelection>,
    acknowledgedReasonCodes: Set<String>,
    actorId: String,
  ): DedupResolution {
    refreshClusterForSubmission(clusterId)
    val plan = eligibilityPolicy.validateCustom(clusterId, expectedRevision, stateRevision, selections, acknowledgedReasonCodes)
    val suggestion = suggestionPlanner.evaluate(clusterId)
    if (suggestion.eligibility.blockers.any { it.code in setOf("CLUSTER_NOT_UNPROCESSED", "CLUSTER_REVISION_STALE", "MEMBER_RESOLUTION_ACTIVE", "STATE_REVISION_CHANGED") }) {
      validation("PROCESSING_INELIGIBLE", "Cluster processing requirements are not satisfied")
    }
    return executeNew(clusterId, expectedRevision, stateRevision, DedupResolutionMode.CUSTOM, plan, suggestion, actorId)
  }

  fun retry(resolutionId: String): DedupResolution {
    val value = resolutionRepository.findResolution(resolutionId) ?: validation("RESOLUTION_NOT_FOUND", "Resolution was not found")
    if (value.state !in setOf(DedupResolutionState.NEEDS_ATTENTION, DedupResolutionState.PARTIALLY_COMPLETED)) {
      validation("RESOLUTION_NOT_RETRYABLE", "Resolution is not waiting for retry")
    }
    val cluster = dedupRepository.findCluster(value.clusterId) ?: validation("CLUSTER_NOT_FOUND", "Cluster was not found")
    if (!dedupRepository.updateClusterState(value.clusterId, setOf(DedupClusterStatus.NEEDS_ATTENTION), DedupClusterStatus.PROCESSING, resolutionId)) {
      validation("CLUSTER_NOT_RETRYABLE", "Cluster state changed")
    }
    val token =
      java.util.UUID
        .randomUUID()
        .toString()
    if (!resolutionRepository.updateResolution(
        resolutionId,
        setOf(value.state),
        DedupResolutionState.PROCESSING,
        value.resultJson,
        leaseToken = token,
        leaseUntil = LocalDateTime.now().plus(leaseDuration),
      )
    ) {
      dedupRepository.updateClusterState(value.clusterId, setOf(DedupClusterStatus.PROCESSING), DedupClusterStatus.NEEDS_ATTENTION, resolutionId)
      validation("RESOLUTION_CHANGED", "Resolution state changed")
    }
    val processing = requireNotNull(resolutionRepository.findResolution(resolutionId))
    return try {
      prepareRetry(
        processing,
        cluster.members
          .filter { it.present }
          .map { it.bookId }
          .toSet(),
      )
      continueExecution(requireNotNull(resolutionRepository.findResolution(resolutionId)))
    } catch (exception: DedupResolutionExecutionException) {
      throw exception
    } catch (exception: Exception) {
      failResolution(processing, null, "RETRY_FAILED", exception.message ?: exception.javaClass.simpleName)
    }
  }

  @Transactional
  fun abandon(resolutionId: String): DedupResolution {
    val value = resolutionRepository.findResolution(resolutionId) ?: validation("RESOLUTION_NOT_FOUND", "Resolution was not found")
    if (value.state != DedupResolutionState.NEEDS_ATTENTION) validation("RESOLUTION_NOT_ABANDONABLE", "Resolution is not waiting for reapproval")
    val members = resolutionRepository.findResolutionMembers(resolutionId)
    val irreversible =
      members.any {
        it.state in
          setOf(
            DedupResolutionMemberState.DELETED,
            DedupResolutionMemberState.KOMGA_SAVED,
            DedupResolutionMemberState.GORSE_CONFIRMED,
            DedupResolutionMemberState.COMPLETED,
          ) || (it.action == DedupResolutionAction.DELETE && Files.notExists(Path.of(it.expectedPath ?: it.pathSnapshot)))
      }
    if (irreversible) validation("RESOLUTION_HAS_IRREVERSIBLE_PROGRESS", "A resolution with irreversible progress cannot be abandoned")
    val cluster = dedupRepository.findCluster(value.clusterId) ?: validation("CLUSTER_NOT_FOUND", "Cluster was not found")
    if (cluster.cluster.status != DedupClusterStatus.NEEDS_ATTENTION) validation("CLUSTER_NOT_REAPPROVABLE", "Cluster state changed")
    val now = LocalDateTime.now()
    val result = objectMapper.writeValueAsString(mapOf("code" to "REAPPROVAL_REQUESTED", "previousResult" to objectMapper.readTree(value.resultJson)))
    check(
      resolutionRepository.updateResolution(
        resolutionId,
        setOf(DedupResolutionState.NEEDS_ATTENTION),
        DedupResolutionState.ABANDONED,
        result,
        completedDate = now,
        now = now,
      ),
    ) { "Resolution state changed during abandonment" }
    check(
      dedupRepository.updateClusterState(
        value.clusterId,
        setOf(DedupClusterStatus.NEEDS_ATTENTION),
        DedupClusterStatus.UNPROCESSED,
        resolutionId,
        "REAPPROVAL_REQUIRED",
        now,
      ),
    ) { "Cluster state changed during abandonment" }
    return requireNotNull(resolutionRepository.findResolution(resolutionId))
  }

  private fun executeNew(
    clusterId: String,
    expectedRevision: Long,
    stateRevision: String,
    mode: DedupResolutionMode,
    plan: DedupResolutionPlan,
    suggestion: DedupSuggestion,
    actorId: String,
  ): DedupResolution {
    val cluster = requireNotNull(dedupRepository.findCluster(clusterId))
    val presentIds =
      cluster.members
        .filter { it.present }
        .map { it.bookId }
        .toSet()
    if (plan.members.map { it.bookId }.toSet() != presentIds) validation("INCOMPLETE_PLAN", "Plan does not cover the current cluster")
    if (!dedupRepository.claimCluster(clusterId, expectedRevision, stateRevision)) validation("CLUSTER_STALE", "Cluster could not be locked")
    val now = LocalDateTime.now()
    val id = TsidCreator.getTsid256().toString()
    try {
      val relations = dedupRepository.findRelationsForBooks(presentIds).associateBy { it.id }
      val memberRows =
        plan.members.map { planned ->
          val book = bookRepository.findByIdOrNull(planned.bookId) ?: validation("MEMBER_NOT_FOUND", "Book ${planned.bookId} was not found")
          val identity = coverLifecycle.currentSourceIdentity(planned.bookId) ?: validation("MEMBER_STALE", "Book ${planned.bookId} is no longer in scope")
          val localState = localStateLifecycle.snapshot(planned.bookId)
          val relation = planned.directRelationId?.let(relations::get)
          DedupResolutionMember(
            resolutionId = id,
            bookId = book.id,
            seriesId = book.seriesId,
            libraryId = book.libraryId,
            action = planned.action,
            keeperBookId = planned.keeperBookId,
            titleSnapshot = book.name,
            pathSnapshot = book.path.toString(),
            sourceGenerationsJson =
              objectMapper.writeValueAsString(
                mapOf(
                  "content" to identity.contentGeneration,
                  "cover" to identity.coverGeneration,
                  "metadata" to identity.metadataGeneration,
                  "scope" to identity.seriesScopeRevision,
                ),
              ),
            localStateSnapshotJson = objectMapper.writeValueAsString(localState),
            directRelationId = relation?.id,
            directRelationSnapshotJson = relation?.let(objectMapper::writeValueAsString),
            expectedPath = null,
            expectedSize = null,
            expectedArchiveHash = null,
            state = DedupResolutionMemberState.PLANNED,
            resultCode = null,
            resultJson = null,
            lastError = null,
            createdDate = now,
            lastModifiedDate = now,
          )
        }
      val value =
        DedupResolution(
          id = id,
          clusterId = clusterId,
          clusterRevision = expectedRevision,
          mode = mode,
          planRevision = plan.revision,
          planJson = objectMapper.writeValueAsString(plan),
          evidenceJson = objectMapper.writeValueAsString(relations.values.sortedBy { it.id }),
          eligibilityJson = objectMapper.writeValueAsString(suggestion.eligibility),
          ruleVersion = DedupClusterLifecycle.ELIGIBILITY_RULE_VERSION,
          state = DedupResolutionState.PROCESSING,
          actorId = actorId,
          resultJson = "{}",
          leaseToken =
            java.util.UUID
              .randomUUID()
              .toString(),
          leaseUntil = now.plus(leaseDuration),
          createdDate = now,
          lastModifiedDate = now,
          completedDate = null,
        )
      resolutionRepository.insertResolution(value, memberRows)
      dedupRepository.updateClusterState(clusterId, setOf(DedupClusterStatus.PROCESSING), DedupClusterStatus.PROCESSING, id)
      preflight(value, plan.deleteCount > 0)
      return continueExecution(requireNotNull(resolutionRepository.findResolution(id)))
    } catch (exception: DedupResolutionExecutionException) {
      throw exception
    } catch (exception: Exception) {
      val persisted = resolutionRepository.findResolution(id)
      if (persisted == null) {
        dedupRepository.updateClusterState(clusterId, setOf(DedupClusterStatus.PROCESSING), DedupClusterStatus.UNPROCESSED, reopenReason = "PREFLIGHT_FAILED")
        throw DedupResolutionExecutionException(null, "PREFLIGHT_FAILED", false, exception.message ?: exception.javaClass.simpleName)
      }
      failResolution(persisted, null, "PREFLIGHT_FAILED", exception.message ?: exception.javaClass.simpleName)
    }
  }

  private fun refreshClusterForSubmission(clusterId: String): org.gotson.komga.domain.model.DedupClusterWithMembers {
    val current = dedupRepository.findCluster(clusterId) ?: validation("CLUSTER_NOT_FOUND", "Cluster was not found")
    clusterLifecycle.rebuildLibrary(current.cluster.libraryId)
    return dedupRepository.findCluster(clusterId) ?: validation("CLUSTER_NOT_FOUND", "Cluster was not found after refresh")
  }

  private fun preflight(
    value: DedupResolution,
    hashFiles: Boolean,
  ) {
    val members = resolutionRepository.findResolutionMembers(value.id)
    val strongIdentityBookIds = if (hashFiles) members.strongIdentityBookIds() else emptySet()
    val cluster = requireNotNull(dedupRepository.findCluster(value.clusterId)) { "Cluster no longer exists" }
    val fingerprints = requireNotNull(clusterLifecycle.currentFingerprints(cluster)) { "Cluster source state is unavailable" }
    check(
      fingerprints.topology == cluster.cluster.topologyFingerprint &&
        fingerprints.evidence == cluster.cluster.evidenceFingerprint &&
        fingerprints.state == cluster.cluster.stateFingerprint,
    ) { "Cluster topology, evidence, or processing state changed" }
    val identities = members.mapNotNull { coverLifecycle.currentSourceIdentity(it.bookId) }.associateBy { it.bookId }
    val relations = dedupRepository.findRelationsForBooks(members.map { it.bookId }.toSet()).filter { it.isCurrent(identities) }.associateBy { it.id }
    members.sortedBy { it.bookId }.forEach { member ->
      try {
        val book = requireNotNull(bookRepository.findByIdOrNull(member.bookId)) { "Book no longer exists" }
        check(book.deletedDate == null) { "Book is already soft-deleted" }
        val identity = requireNotNull(identities[member.bookId]) { "Book is no longer active and in scope" }
        check(sourceMatches(member, identity)) { "Book source generation changed" }
        val storedState = objectMapper.readTree(member.localStateSnapshotJson).path("revision").asText()
        check(localStateLifecycle.snapshot(member.bookId).revision == storedState) { "Book local state changed" }
        if (member.action == DedupResolutionAction.DELETE) {
          val keeper = requireNotNull(member.keeperBookId)
          check(members.any { it.bookId == keeper && it.action == DedupResolutionAction.KEEP }) { "DELETE keeper is not a KEEP member" }
          val direct = member.directRelationId?.let(relations::get)
          check(direct != null && direct.id == member.directRelationId) { "Direct relation changed" }
          check(relationSnapshotMatches(member, direct)) { "Direct relation evidence changed" }
          check(physicalDeletionLifecycle.precheck(book).status == DedupFilePrecheckStatus.AVAILABLE) { "Delete path precheck failed" }
        }
        val requiresStrongIdentity = member.bookId in strongIdentityBookIds
        if (requiresStrongIdentity) check(identity.archiveHashState == DedupArchiveHashState.READY && identity.archiveHash != null) { "A current persisted archive hash is required" }
        val strong = if (requiresStrongIdentity) physicalDeletionLifecycle.captureStrongIdentity(book) else null
        if (strong != null) check(strong.archiveHash == identity.archiveHash) { "Archive hash changed; deep verification and reapproval are required" }
        check(
          resolutionRepository.updateResolutionMember(
            value.id,
            member.bookId,
            setOf(DedupResolutionMemberState.PLANNED),
            DedupResolutionMemberState.PREFLIGHTED,
            strong?.path,
            strong?.size,
            strong?.archiveHash,
          ),
        ) { "Resolution member changed during preflight" }
      } catch (exception: Exception) {
        resolutionRepository.updateResolutionMember(
          value.id,
          member.bookId,
          setOf(DedupResolutionMemberState.PLANNED, DedupResolutionMemberState.PREFLIGHTED),
          DedupResolutionMemberState.CONFLICT,
          resultCode = "PREFLIGHT_FAILED",
          lastError = exception.message,
        )
        failResolution(value, member.bookId, "PREFLIGHT_FAILED", exception.message ?: exception.javaClass.simpleName)
      }
    }
  }

  private fun continueExecution(value: DedupResolution): DedupResolution {
    var members = resolutionRepository.findResolutionMembers(value.id)
    val deletions = members.filter { it.action == DedupResolutionAction.DELETE }.sortedBy { it.bookId }
    val pendingDeletions = deletions.filter { it.state == DedupResolutionMemberState.PREFLIGHTED }
    if (pendingDeletions.isNotEmpty()) verifyKeepersImmediatelyBeforeDeletion(value, members)
    for (member in pendingDeletions) {
      val book = bookRepository.findByIdOrNull(member.bookId) ?: failResolution(value, member.bookId, "MEMBER_NOT_FOUND", "Book no longer exists")
      val expected = member.expectedIdentity()
      var deletionRecorded = false
      val result =
        physicalDeletionLifecycle.deleteVerifiedBook(book, expected) {
          check(
            resolutionRepository.updateResolutionMember(
              value.id,
              member.bookId,
              setOf(DedupResolutionMemberState.PREFLIGHTED),
              DedupResolutionMemberState.DELETED,
              resultCode = "DELETED",
            ),
          ) { "Physical deletion progress could not be saved" }
          deletionRecorded = true
        }
      if (result.pathAbsent && !deletionRecorded) {
        deletionRecorded =
          resolutionRepository.updateResolutionMember(
            value.id,
            member.bookId,
            setOf(DedupResolutionMemberState.PREFLIGHTED),
            DedupResolutionMemberState.DELETED,
            resultCode = "DELETED",
          )
      }
      if (!result.pathAbsent) {
        val state =
          if (result.code in
            setOf(
              org.gotson.komga.domain.model.DedupDeletionResultCode.GENERATION_MISMATCH,
              org.gotson.komga.domain.model.DedupDeletionResultCode.PATH_MISSING_UNCONFIRMED,
            )
          )
            DedupResolutionMemberState.CONFLICT
          else
            DedupResolutionMemberState.FAILED
        resolutionRepository.updateResolutionMember(
          value.id,
          member.bookId,
          setOf(DedupResolutionMemberState.PREFLIGHTED),
          state,
          resultCode = result.code.name,
          resultJson = objectMapper.writeValueAsString(result),
          lastError = result.detail,
        )
        failResolution(value, member.bookId, result.code.name, result.detail ?: "Book deletion failed")
      }
      if (!deletionRecorded) failResolution(value, member.bookId, "DELETION_PROGRESS_NOT_SAVED", "Physical deletion progress could not be saved")
      if (!result.databaseSoftDeleted) {
        resolutionRepository.updateResolutionMember(
          value.id,
          member.bookId,
          setOf(DedupResolutionMemberState.DELETED),
          DedupResolutionMemberState.DELETED,
          resultCode = result.code.name,
          resultJson = objectMapper.writeValueAsString(result),
          lastError = result.detail,
        )
        failResolution(value, member.bookId, result.code.name, result.detail ?: "Komga soft deletion failed")
      }
      val persisted = bookRepository.findByIdOrNull(member.bookId)
      if (persisted?.deletedDate == null) {
        resolutionRepository.updateResolutionMember(
          value.id,
          member.bookId,
          setOf(DedupResolutionMemberState.DELETED),
          DedupResolutionMemberState.DELETED,
          resultCode = "KOMGA_NOT_SAVED",
          resultJson = objectMapper.writeValueAsString(result),
          lastError = "Komga soft deletion could not be read back",
        )
        failResolution(value, member.bookId, "KOMGA_NOT_SAVED", "Komga soft deletion could not be read back")
      }
      resolutionRepository.updateResolutionMember(
        value.id,
        member.bookId,
        setOf(DedupResolutionMemberState.DELETED),
        DedupResolutionMemberState.KOMGA_SAVED,
        resultCode = result.code.name,
        resultJson = objectMapper.writeValueAsString(result),
      )
    }

    members = resolutionRepository.findResolutionMembers(value.id)
    val seriesResults = linkedMapOf<String, Any>()
    runCatching { objectMapper.readTree(value.resultJson).path("series") }.getOrNull()?.fields()?.forEachRemaining { seriesResults[it.key] = it.value }
    members
      .filter { it.action == DedupResolutionAction.DELETE && it.state == DedupResolutionMemberState.KOMGA_SAVED }
      .groupBy { it.seriesId }
      .toSortedMap()
      .forEach { (seriesId, seriesMembers) ->
        val result = gorseDesiredStateLifecycle.syncNow(seriesId, seriesMembers.first().libraryId)
        seriesResults[seriesId] = result
        if (result.state == GorseSyncNowState.FAILED) failResolution(value, null, "GORSE_FAILED", result.error ?: "Gorse confirmation failed", seriesResults)
        seriesMembers.forEach { member ->
          resolutionRepository.updateResolutionMember(
            value.id,
            member.bookId,
            setOf(DedupResolutionMemberState.KOMGA_SAVED),
            DedupResolutionMemberState.GORSE_CONFIRMED,
            resultCode = result.state.name,
            resultJson = objectMapper.writeValueAsString(result),
          )
        }
      }

    members = resolutionRepository.findResolutionMembers(value.id)
    members.forEach { member ->
      val expected = if (member.action == DedupResolutionAction.KEEP) setOf(DedupResolutionMemberState.PREFLIGHTED) else setOf(DedupResolutionMemberState.GORSE_CONFIRMED)
      check(
        resolutionRepository.updateResolutionMember(
          value.id,
          member.bookId,
          expected,
          DedupResolutionMemberState.COMPLETED,
          resultCode = member.resultCode,
          resultJson = member.resultJson,
        ),
      ) { "Resolution member could not be finalized" }
    }
    val survivors = members.filter { it.action == DedupResolutionAction.KEEP }.map { it.bookId }.toSet()
    clusterLifecycle.finalizeProcessed(value.clusterId, value.id, survivors)
    val resultJson = objectMapper.writeValueAsString(mapOf("series" to seriesResults, "deleted" to deletions.map { it.bookId }, "kept" to survivors.sorted()))
    if (!resolutionRepository.updateResolution(value.id, setOf(DedupResolutionState.PROCESSING), DedupResolutionState.PROCESSED, resultJson, LocalDateTime.now())) {
      dedupRepository.updateClusterState(value.clusterId, setOf(DedupClusterStatus.PROCESSED), DedupClusterStatus.NEEDS_ATTENTION, value.id, "FINALIZE_CONFLICT")
      throw DedupResolutionExecutionException(value.id, "FINALIZE_CONFLICT", deletions.isNotEmpty(), "Resolution final state could not be saved")
    }
    return requireNotNull(resolutionRepository.findResolution(value.id))
  }

  private fun verifyKeepersImmediatelyBeforeDeletion(
    value: DedupResolution,
    members: List<DedupResolutionMember>,
  ) {
    val keeperIds = members.filter { it.action == DedupResolutionAction.DELETE }.mapNotNull { it.keeperBookId }.toSet()
    members.filter { it.bookId in keeperIds && it.state == DedupResolutionMemberState.PREFLIGHTED }.sortedBy { it.bookId }.forEach { member ->
      try {
        val book = requireNotNull(bookRepository.findByIdOrNull(member.bookId)) { "Keeper no longer exists" }
        val current = physicalDeletionLifecycle.captureStrongIdentity(book)
        check(current.matches(member.expectedIdentity())) { "Keeper path, size, or archive hash changed" }
      } catch (exception: Exception) {
        resolutionRepository.updateResolutionMember(
          value.id,
          member.bookId,
          setOf(DedupResolutionMemberState.PREFLIGHTED),
          DedupResolutionMemberState.CONFLICT,
          resultCode = "KEEPER_CHANGED",
          lastError = exception.message,
        )
        failResolution(value, member.bookId, "KEEPER_CHANGED", exception.message ?: exception.javaClass.simpleName)
      }
    }
  }

  private fun prepareRetry(
    value: DedupResolution,
    clusterMemberIds: Set<String>,
  ) {
    val members = resolutionRepository.findResolutionMembers(value.id)
    val strongIdentityBookIds = members.strongIdentityBookIds()
    members.forEach { member ->
      when (member.state) {
        DedupResolutionMemberState.COMPLETED, DedupResolutionMemberState.GORSE_CONFIRMED -> Unit
        DedupResolutionMemberState.KOMGA_SAVED, DedupResolutionMemberState.DELETED -> {
          val path = Path.of(requireNotNull(member.expectedPath))
          if (Files.exists(path)) {
            val book = requireNotNull(bookRepository.findByIdOrNull(member.bookId))
            val current = runCatching { physicalDeletionLifecycle.captureStrongIdentity(book, requireDatabaseStat = false) }.getOrNull()
            val code = if (current?.archiveHash == member.expectedArchiveHash) "REAPPEARED_SAME_HASH" else "REAPPEARED_DIFFERENT_HASH"
            resolutionRepository.updateResolutionMember(value.id, member.bookId, setOf(member.state), DedupResolutionMemberState.CONFLICT, resultCode = code, lastError = "Deleted path reappeared")
            failResolution(value, member.bookId, code, "Deleted path reappeared")
          }
          val book = requireNotNull(bookRepository.findByIdOrNull(member.bookId))
          val confirmed = physicalDeletionLifecycle.confirmPathAbsentAndSoftDelete(book)
          if (!confirmed.databaseSoftDeleted) failResolution(value, member.bookId, "KOMGA_NOT_SAVED", confirmed.detail ?: "Komga soft deletion failed")
          resolutionRepository.updateResolutionMember(
            value.id,
            member.bookId,
            setOf(member.state),
            DedupResolutionMemberState.KOMGA_SAVED,
            resultCode = confirmed.code.name,
            resultJson = objectMapper.writeValueAsString(confirmed),
          )
        }
        DedupResolutionMemberState.FAILED, DedupResolutionMemberState.PLANNED -> {
          if (member.bookId !in clusterMemberIds) failResolution(value, member.bookId, "MEMBER_STALE", "Member is no longer part of the cluster")
          val book = requireNotNull(bookRepository.findByIdOrNull(member.bookId))
          val identity = coverLifecycle.currentSourceIdentity(member.bookId) ?: failResolution(value, member.bookId, "MEMBER_STALE", "Member is no longer in scope")
          if (!sourceMatches(member, identity)) failResolution(value, member.bookId, "GENERATION_MISMATCH", "Member generation changed; create a new resolution")
          val storedState = objectMapper.readTree(member.localStateSnapshotJson).path("revision").asText()
          if (localStateLifecycle.snapshot(member.bookId).revision != storedState) failResolution(value, member.bookId, "STATE_CHANGED", "Member local state changed; create a new resolution")
          if (member.action == DedupResolutionAction.DELETE) {
            val keeperId = requireNotNull(member.keeperBookId)
            val keeperIdentity = coverLifecycle.currentSourceIdentity(keeperId) ?: failResolution(value, keeperId, "KEEPER_STALE", "Keeper is no longer in scope")
            val relation = dedupRepository.findRelation(member.bookId, keeperId) ?: failResolution(value, member.bookId, "RELATION_CHANGED", "Direct relation is no longer available")
            if (!relation.isCurrent(mapOf(identity.bookId to identity, keeperIdentity.bookId to keeperIdentity)) || !relationSnapshotMatches(member, relation)) {
              failResolution(value, member.bookId, "RELATION_CHANGED", "Direct relation changed; create a new resolution")
            }
          }
          val strong = if (member.bookId in strongIdentityBookIds) physicalDeletionLifecycle.captureStrongIdentity(book) else null
          if (member.expectedArchiveHash != null && strong?.archiveHash != member.expectedArchiveHash) failResolution(value, member.bookId, "GENERATION_MISMATCH", "Archive hash changed; create a new resolution")
          resolutionRepository.updateResolutionMember(
            value.id,
            member.bookId,
            setOf(member.state),
            DedupResolutionMemberState.PREFLIGHTED,
            strong?.path,
            strong?.size,
            strong?.archiveHash,
          )
        }
        DedupResolutionMemberState.CONFLICT -> failResolution(value, member.bookId, "REAPPROVAL_REQUIRED", "A conflict requires a new resolution")
        DedupResolutionMemberState.PREFLIGHTED -> Unit
      }
    }
  }

  private fun failResolution(
    value: DedupResolution,
    bookId: String?,
    code: String,
    message: String,
    seriesResults: Map<String, Any> = emptyMap(),
  ): Nothing {
    val members = resolutionRepository.findResolutionMembers(value.id)
    val partial = members.any { it.state in setOf(DedupResolutionMemberState.DELETED, DedupResolutionMemberState.KOMGA_SAVED, DedupResolutionMemberState.GORSE_CONFIRMED, DedupResolutionMemberState.COMPLETED) }
    val state = if (partial) DedupResolutionState.PARTIALLY_COMPLETED else DedupResolutionState.NEEDS_ATTENTION
    val result = objectMapper.writeValueAsString(mapOf("code" to code, "bookId" to bookId, "message" to message, "series" to seriesResults))
    resolutionRepository.updateResolution(value.id, setOf(DedupResolutionState.PROCESSING), state, result)
    dedupRepository.updateClusterState(value.clusterId, setOf(DedupClusterStatus.PROCESSING, DedupClusterStatus.PROCESSED), DedupClusterStatus.NEEDS_ATTENTION, value.id, code)
    throw DedupResolutionExecutionException(value.id, code, partial, message)
  }

  private fun sourceMatches(
    member: DedupResolutionMember,
    identity: org.gotson.komga.domain.model.DedupSourceIdentity,
  ): Boolean {
    val source = objectMapper.readTree(member.sourceGenerationsJson)
    return source.path("content").asText() == identity.contentGeneration && source.path("cover").asText() == identity.coverGeneration &&
      source.path("metadata").asText() == identity.metadataGeneration && source.path("scope").asText() == identity.seriesScopeRevision
  }

  private fun relationSnapshotMatches(
    member: DedupResolutionMember,
    relation: org.gotson.komga.domain.model.DedupRelation,
  ): Boolean {
    val snapshot = objectMapper.readTree(member.directRelationSnapshotJson ?: return false)
    val current = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(relation)
    listOf("createdDate", "lastModifiedDate").forEach { field ->
      (snapshot as? com.fasterxml.jackson.databind.node.ObjectNode)?.remove(field)
      (current as? com.fasterxml.jackson.databind.node.ObjectNode)?.remove(field)
    }
    return snapshot == current
  }

  private fun List<DedupResolutionMember>.strongIdentityBookIds(): Set<String> =
    filter { it.action == DedupResolutionAction.DELETE }
      .flatMap { listOf(it.bookId, requireNotNull(it.keeperBookId)) }
      .toSet()

  private fun DedupResolutionMember.expectedIdentity() =
    DedupStrongFileIdentity(
      requireNotNull(expectedPath),
      requireNotNull(expectedSize),
      requireNotNull(expectedArchiveHash),
    )

  private fun validation(
    code: String,
    message: String,
  ): Nothing = throw DedupResolutionValidationException(code, message)
}
