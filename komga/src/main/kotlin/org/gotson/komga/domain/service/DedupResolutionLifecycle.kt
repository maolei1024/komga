package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.f4b6a3.tsid.TsidCreator
import org.gotson.komga.application.tasks.TaskEmitter
import org.gotson.komga.domain.model.DedupArchiveHashState
import org.gotson.komga.domain.model.DedupClusterStatus
import org.gotson.komga.domain.model.DedupPlanMember
import org.gotson.komga.domain.model.DedupResolution
import org.gotson.komga.domain.model.DedupResolutionAction
import org.gotson.komga.domain.model.DedupResolutionMember
import org.gotson.komga.domain.model.DedupResolutionMemberState
import org.gotson.komga.domain.model.DedupResolutionMode
import org.gotson.komga.domain.model.DedupResolutionPlan
import org.gotson.komga.domain.model.DedupResolutionState
import org.gotson.komga.domain.model.DedupSourceIdentity
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.DedupResolutionRepository
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime

class DedupResolutionValidationException(
  val code: String,
  message: String,
) : IllegalStateException(message)

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
  private val coverLifecycle: DedupCoverLifecycle,
  private val physicalDeletionLifecycle: DedupPhysicalBookDeletionLifecycle,
  private val clusterLifecycle: DedupClusterLifecycle,
  private val taskEmitter: TaskEmitter,
  private val objectMapper: ObjectMapper,
) {
  private val leaseDuration = Duration.ofMinutes(30)

  fun createSuggested(
    clusterId: String,
    expectedRevision: Long,
    actorId: String,
  ): DedupResolution {
    val value = refreshClusterForSubmission(clusterId, expectedRevision)
    val plan = suggestionPlanner.evaluate(value).plan ?: validation("SUGGESTION_UNAVAILABLE", "No unique safe keeper is available")
    return executeNew(value, DedupResolutionMode.SUGGESTED, plan, actorId)
  }

  fun createCustom(
    clusterId: String,
    expectedRevision: Long,
    deleteBookIds: List<String>,
    actorId: String,
  ): DedupResolution {
    if (deleteBookIds.distinct().size != deleteBookIds.size) validation("DUPLICATE_MEMBER", "A Book appears more than once")
    val value = refreshClusterForSubmission(clusterId, expectedRevision)
    val presentIds =
      value.members
        .filter { it.present }
        .map { it.bookId }
        .toSet()
    val deletes = deleteBookIds.toSet()
    if (!presentIds.containsAll(deletes)) validation("MEMBER_NOT_FOUND", "Every delete ID must be a current cluster member")
    if (deletes == presentIds) validation("DELETE_ALL_FORBIDDEN", "At least one Book must be retained")
    val members =
      presentIds.sorted().map { id ->
        DedupPlanMember(id, if (id in deletes) DedupResolutionAction.DELETE else DedupResolutionAction.KEEP)
      }
    val plan = DedupResolutionPlan(stableHash(canonicalPlan(members)), members)
    return executeNew(value, DedupResolutionMode.CUSTOM, plan, actorId)
  }

  fun retry(resolutionId: String): DedupResolution {
    val value = resolutionRepository.findResolution(resolutionId) ?: validation("RESOLUTION_NOT_FOUND", "Resolution was not found")
    if (value.state != DedupResolutionState.PARTIALLY_COMPLETED) validation("RESOLUTION_NOT_RETRYABLE", "Only a partial deletion can be retried")
    if (!dedupRepository.updateClusterState(value.clusterId, setOf(DedupClusterStatus.NEEDS_ATTENTION), DedupClusterStatus.PROCESSING, resolutionId)) {
      validation("CLUSTER_NOT_RETRYABLE", "Cluster state changed")
    }
    val token =
      java.util.UUID
        .randomUUID()
        .toString()
    if (!resolutionRepository.updateResolution(
        resolutionId,
        setOf(DedupResolutionState.PARTIALLY_COMPLETED),
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
      val cluster = dedupRepository.findCluster(value.clusterId) ?: validation("CLUSTER_NOT_FOUND", "Cluster was not found")
      taskEmitter.executeDedupResolution(processing.id, cluster.cluster.libraryId)
      processing
    } catch (exception: DedupResolutionExecutionException) {
      throw exception
    } catch (exception: Exception) {
      failResolution(processing, null, "RETRY_FAILED", exception.message ?: exception.javaClass.simpleName)
    }
  }

  fun executeQueued(resolutionId: String): DedupResolution {
    val current = resolutionRepository.findResolution(resolutionId) ?: validation("RESOLUTION_NOT_FOUND", "Resolution was not found")
    if (current.state != DedupResolutionState.PROCESSING) return current
    return try {
      val token =
        java.util.UUID
          .randomUUID()
          .toString()
      if (!resolutionRepository.updateResolution(
          current.id,
          setOf(DedupResolutionState.PROCESSING),
          DedupResolutionState.PROCESSING,
          current.resultJson,
          leaseToken = token,
          leaseUntil = LocalDateTime.now().plus(leaseDuration),
        )
      ) {
        return resolutionRepository.findResolution(resolutionId) ?: validation("RESOLUTION_NOT_FOUND", "Resolution was not found")
      }
      val processing = requireNotNull(resolutionRepository.findResolution(resolutionId))
      val members = resolutionRepository.findResolutionMembers(processing.id)
      if (members.any { it.state.requiresResumePreparation() }) {
        prepareRetry(processing)
      } else {
        preflight(processing)
      }
      continueExecution(requireNotNull(resolutionRepository.findResolution(processing.id)))
    } catch (exception: DedupResolutionExecutionException) {
      throw exception
    } catch (exception: Exception) {
      failResolution(current, null, "EXECUTION_FAILED", exception.message ?: exception.javaClass.simpleName)
    }
  }

  private fun refreshClusterForSubmission(
    clusterId: String,
    expectedRevision: Long,
  ): org.gotson.komga.domain.model.DedupClusterWithMembers {
    val current = dedupRepository.findCluster(clusterId) ?: validation("CLUSTER_NOT_FOUND", "Cluster was not found")
    clusterLifecycle.rebuildLibrary(current.cluster.libraryId)
    val refreshed = dedupRepository.findCluster(clusterId) ?: validation("CLUSTER_STALE", "Cluster is no longer reviewable")
    if (refreshed.cluster.revision != expectedRevision || refreshed.cluster.status != DedupClusterStatus.UNPROCESSED || !refreshed.cluster.reviewable) {
      validation("CLUSTER_STALE", "Cluster revision changed")
    }
    val fingerprints = clusterLifecycle.currentFingerprints(refreshed)
    if (fingerprints == null || fingerprints != ClusterFingerprints(refreshed.cluster.topologyFingerprint, refreshed.cluster.evidenceFingerprint, refreshed.cluster.stateFingerprint)) {
      validation("CLUSTER_STALE", "Cluster evidence changed")
    }
    if (resolutionRepository.hasActiveResolutionForBooks(
        refreshed.members
          .filter { it.present }
          .map { it.bookId }
          .toSet(),
      )
    ) {
      validation("MEMBER_RESOLUTION_ACTIVE", "A cluster member is already being processed")
    }
    return refreshed
  }

  private fun executeNew(
    cluster: org.gotson.komga.domain.model.DedupClusterWithMembers,
    mode: DedupResolutionMode,
    plan: DedupResolutionPlan,
    actorId: String,
  ): DedupResolution {
    val presentIds =
      cluster.members
        .filter { it.present }
        .map { it.bookId }
        .toSet()
    if (plan.members.map { it.bookId }.toSet() != presentIds) validation("INCOMPLETE_PLAN", "Plan does not cover the current cluster")
    if (!dedupRepository.claimCluster(cluster.cluster.id, cluster.cluster.revision, cluster.cluster.stateFingerprint)) {
      validation("CLUSTER_STALE", "Cluster could not be locked")
    }
    val now = LocalDateTime.now()
    val id = TsidCreator.getTsid256().toString()
    try {
      val relations = clusterLifecycle.currentReviewRelations(presentIds).associateBy { it.id }
      val memberRows =
        plan.members.map { planned ->
          val book = bookRepository.findByIdOrNull(planned.bookId) ?: validation("MEMBER_NOT_FOUND", "Book ${planned.bookId} was not found")
          val identity = coverLifecycle.currentSourceIdentity(planned.bookId) ?: validation("MEMBER_STALE", "Book ${planned.bookId} is no longer active")
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
            sourceGenerationsJson = objectMapper.writeValueAsString(sourceSnapshot(identity)),
            localStateSnapshotJson = "{}",
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
          clusterId = cluster.cluster.id,
          clusterRevision = cluster.cluster.revision,
          mode = mode,
          planRevision = plan.revision,
          planJson = objectMapper.writeValueAsString(plan),
          evidenceJson = objectMapper.writeValueAsString(relations.values.sortedBy { it.id }),
          eligibilityJson = "{}",
          ruleVersion = DedupClusterLifecycle.RULE_VERSION,
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
      dedupRepository.updateClusterState(cluster.cluster.id, setOf(DedupClusterStatus.PROCESSING), DedupClusterStatus.PROCESSING, id)
      taskEmitter.executeDedupResolution(id, cluster.cluster.libraryId)
      return value
    } catch (exception: DedupResolutionExecutionException) {
      throw exception
    } catch (exception: Exception) {
      val persisted = resolutionRepository.findResolution(id)
      if (persisted == null) {
        dedupRepository.updateClusterState(cluster.cluster.id, setOf(DedupClusterStatus.PROCESSING), DedupClusterStatus.UNPROCESSED, reopenReason = "SUBMISSION_FAILED")
        throw DedupResolutionExecutionException(null, "SUBMISSION_FAILED", false, sanitize(exception.message ?: exception.javaClass.simpleName))
      }
      failResolution(persisted, null, "QUEUE_FAILED", exception.message ?: exception.javaClass.simpleName)
    }
  }

  /** Captures every delete identity before the first unlink. */
  private fun preflight(value: DedupResolution) {
    val members = resolutionRepository.findResolutionMembers(value.id)
    val cluster = requireNotNull(dedupRepository.findCluster(value.clusterId)) { "Cluster no longer exists" }
    val fingerprints = requireNotNull(clusterLifecycle.currentFingerprints(cluster)) { "Cluster source state is unavailable" }
    check(fingerprints == ClusterFingerprints(cluster.cluster.topologyFingerprint, cluster.cluster.evidenceFingerprint, cluster.cluster.stateFingerprint)) {
      "Cluster evidence changed"
    }
    var firstFailure: Pair<String, String>? = null
    members.sortedBy { it.bookId }.forEach { member ->
      try {
        if (member.state == DedupResolutionMemberState.PREFLIGHTED) return@forEach
        check(member.state == DedupResolutionMemberState.PLANNED) { "Resolution member cannot enter preflight from ${member.state}" }
        if (member.action == DedupResolutionAction.KEEP) {
          check(
            resolutionRepository.updateResolutionMember(
              value.id,
              member.bookId,
              setOf(DedupResolutionMemberState.PLANNED),
              DedupResolutionMemberState.PREFLIGHTED,
            ),
          ) { "Resolution member changed during preflight" }
          return@forEach
        }
        val book = requireNotNull(bookRepository.findByIdOrNull(member.bookId)) { "Book no longer exists" }
        check(book.deletedDate == null) { "Book is already soft-deleted" }
        val identity = requireNotNull(coverLifecycle.currentSourceIdentity(member.bookId)) { "Book is no longer active" }
        check(sourceMatches(member, identity)) { "Book source generation changed" }
        check(physicalDeletionLifecycle.precheck(book).status == DedupFilePrecheckStatus.AVAILABLE) { "Delete path precheck failed" }
        check(identity.archiveHashState == DedupArchiveHashState.READY && !identity.archiveHash.isNullOrBlank()) {
          "Current persisted archive hash is unavailable"
        }
        // Deep verification already read and persisted the complete archive hash. Use that
        // approved identity here; deleteVerifiedBook still hashes the live path immediately
        // before unlinking it, so the destructive boundary keeps its strong verification
        // without reading every remote archive twice during one submission.
        val strong =
          DedupStrongFileIdentity(
            book.path
              .toAbsolutePath()
              .normalize()
              .toString(),
            book.fileSize,
            requireNotNull(identity.archiveHash),
          )
        check(
          resolutionRepository.updateResolutionMember(
            value.id,
            member.bookId,
            setOf(DedupResolutionMemberState.PLANNED),
            DedupResolutionMemberState.PREFLIGHTED,
            strong.path,
            strong.size,
            strong.archiveHash,
          ),
        ) { "Resolution member changed during preflight" }
      } catch (exception: Exception) {
        val message = sanitize(exception.message ?: exception.javaClass.simpleName)
        resolutionRepository.updateResolutionMember(
          value.id,
          member.bookId,
          setOf(DedupResolutionMemberState.PLANNED, DedupResolutionMemberState.PREFLIGHTED),
          DedupResolutionMemberState.CONFLICT,
          resultCode = "PREFLIGHT_FAILED",
          lastError = message,
        )
        if (firstFailure == null) firstFailure = member.bookId to message
      }
    }
    firstFailure?.let { (bookId, message) -> failResolution(value, bookId, "PREFLIGHT_FAILED", message) }
  }

  private fun continueExecution(value: DedupResolution): DedupResolution {
    var members = resolutionRepository.findResolutionMembers(value.id)
    val deletions = members.filter { it.action == DedupResolutionAction.DELETE }.sortedBy { it.bookId }
    for (member in deletions.filter { it.state == DedupResolutionMemberState.PREFLIGHTED }) {
      val book = bookRepository.findByIdOrNull(member.bookId) ?: failResolution(value, member.bookId, "MEMBER_NOT_FOUND", "Book no longer exists")
      var deletionRecorded = false
      val result =
        physicalDeletionLifecycle.deleteVerifiedBook(book, member.expectedIdentity()) {
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
        resolutionRepository.updateResolutionMember(
          value.id,
          member.bookId,
          setOf(DedupResolutionMemberState.PREFLIGHTED),
          DedupResolutionMemberState.FAILED,
          resultCode = result.code.name,
          resultJson = objectMapper.writeValueAsString(result),
          lastError = sanitize(result.detail ?: "Book deletion failed"),
        )
        failResolution(value, member.bookId, result.code.name, result.detail ?: "Book deletion failed")
      }
      if (!deletionRecorded) failResolution(value, member.bookId, "DELETION_PROGRESS_NOT_SAVED", "Physical deletion progress could not be saved")
      if (!result.databaseSoftDeleted || bookRepository.findByIdOrNull(member.bookId)?.deletedDate == null) {
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
    members.filter { it.state != DedupResolutionMemberState.COMPLETED }.forEach { member ->
      val expected =
        if (member.action == DedupResolutionAction.KEEP) setOf(DedupResolutionMemberState.PREFLIGHTED) else setOf(DedupResolutionMemberState.KOMGA_SAVED)
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
    val cluster = requireNotNull(dedupRepository.findCluster(value.clusterId)) { "Cluster no longer exists" }
    if (cluster.cluster.status == DedupClusterStatus.PROCESSING) {
      clusterLifecycle.finalizeProcessed(value.clusterId, value.id, value.actorId, survivors)
    } else {
      check(cluster.cluster.status == DedupClusterStatus.PROCESSED && cluster.cluster.lastResolutionId == value.id) {
        "Cluster final state does not belong to this resolution"
      }
    }
    val resultJson = objectMapper.writeValueAsString(mapOf("deleted" to deletions.map { it.bookId }, "kept" to survivors.sorted()))
    if (!resolutionRepository.updateResolution(value.id, setOf(DedupResolutionState.PROCESSING), DedupResolutionState.PROCESSED, resultJson, LocalDateTime.now())) {
      throw DedupResolutionExecutionException(value.id, "FINALIZE_CONFLICT", deletions.isNotEmpty(), "Resolution final state could not be saved")
    }
    return requireNotNull(resolutionRepository.findResolution(value.id))
  }

  private fun prepareRetry(value: DedupResolution) {
    resolutionRepository.findResolutionMembers(value.id).forEach { member ->
      when (member.state) {
        DedupResolutionMemberState.COMPLETED -> Unit
        DedupResolutionMemberState.KOMGA_SAVED, DedupResolutionMemberState.DELETED -> {
          val path = Path.of(requireNotNull(member.expectedPath))
          if (Files.exists(path)) failResolution(value, member.bookId, "PATH_REAPPEARED", "Deleted path reappeared")
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
        DedupResolutionMemberState.FAILED -> {
          check(member.action == DedupResolutionAction.DELETE) { "Only a failed deletion is retryable" }
          val book = requireNotNull(bookRepository.findByIdOrNull(member.bookId))
          val current = physicalDeletionLifecycle.captureStrongIdentity(book)
          check(current.matches(member.expectedIdentity())) { "Delete target identity changed" }
          resolutionRepository.updateResolutionMember(
            value.id,
            member.bookId,
            setOf(DedupResolutionMemberState.FAILED),
            DedupResolutionMemberState.PREFLIGHTED,
          )
        }
        DedupResolutionMemberState.PREFLIGHTED -> Unit
        DedupResolutionMemberState.GORSE_CONFIRMED -> {
          // Legacy rows may reach V2 retry after having completed the retired Gorse step.
          resolutionRepository.updateResolutionMember(
            value.id,
            member.bookId,
            setOf(DedupResolutionMemberState.GORSE_CONFIRMED),
            DedupResolutionMemberState.KOMGA_SAVED,
          )
        }
        DedupResolutionMemberState.PLANNED, DedupResolutionMemberState.CONFLICT ->
          failResolution(value, member.bookId, "REAPPROVAL_REQUIRED", "The previous preflight did not complete")
      }
    }
  }

  private fun failResolution(
    value: DedupResolution,
    bookId: String?,
    code: String,
    message: String,
  ): Nothing {
    val members = resolutionRepository.findResolutionMembers(value.id)
    val partial =
      members.any {
        it.state in
          setOf(
            DedupResolutionMemberState.DELETED,
            DedupResolutionMemberState.KOMGA_SAVED,
            DedupResolutionMemberState.GORSE_CONFIRMED,
            DedupResolutionMemberState.COMPLETED,
          )
      }
    val state = if (partial) DedupResolutionState.PARTIALLY_COMPLETED else DedupResolutionState.NEEDS_ATTENTION
    val safeMessage = sanitize(message)
    val result = objectMapper.writeValueAsString(mapOf("code" to code, "bookId" to bookId, "message" to safeMessage))
    resolutionRepository.updateResolution(value.id, setOf(DedupResolutionState.PROCESSING), state, result)
    dedupRepository.updateClusterState(
      value.clusterId,
      setOf(DedupClusterStatus.PROCESSING),
      if (partial) DedupClusterStatus.NEEDS_ATTENTION else DedupClusterStatus.UNPROCESSED,
      value.id,
      code,
    )
    throw DedupResolutionExecutionException(value.id, code, partial, safeMessage)
  }

  private fun sourceSnapshot(identity: DedupSourceIdentity): Map<String, String> =
    mapOf(
      "content" to identity.contentGeneration,
      "cover" to identity.coverGeneration,
      "metadata" to identity.metadataGeneration,
      "scope" to identity.seriesScopeRevision,
    )

  private fun sourceMatches(
    member: DedupResolutionMember,
    identity: DedupSourceIdentity,
  ): Boolean {
    val source = objectMapper.readTree(member.sourceGenerationsJson)
    return source.path("content").asText() == identity.contentGeneration &&
      source.path("cover").asText() == identity.coverGeneration &&
      source.path("metadata").asText() == identity.metadataGeneration &&
      source.path("scope").asText() == identity.seriesScopeRevision
  }

  private fun DedupResolutionMember.expectedIdentity() = DedupStrongFileIdentity(requireNotNull(expectedPath), requireNotNull(expectedSize), requireNotNull(expectedArchiveHash))

  private fun DedupResolutionMemberState.requiresResumePreparation(): Boolean =
    this in
      setOf(
        DedupResolutionMemberState.DELETED,
        DedupResolutionMemberState.KOMGA_SAVED,
        DedupResolutionMemberState.GORSE_CONFIRMED,
        DedupResolutionMemberState.COMPLETED,
        DedupResolutionMemberState.FAILED,
      )

  private fun canonicalPlan(members: List<DedupPlanMember>): String = members.sortedBy { it.bookId }.joinToString("|") { "${it.bookId}:${it.action}:${it.keeperBookId.orEmpty()}:${it.directRelationId.orEmpty()}" }

  private fun stableHash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

  private fun sanitize(message: String): String = message.replace(Regex("[\\r\\n]+"), " ").take(500)

  private fun validation(
    code: String,
    message: String,
  ): Nothing = throw DedupResolutionValidationException(code, message)
}
