package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationStatus
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupReviewCase
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupDecisionRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.interfaces.api.rest.dto.DedupAction
import org.gotson.komga.interfaces.api.rest.dto.DedupEligibilityReasonDto
import org.gotson.komga.interfaces.api.rest.dto.DedupEligibilityReportDto
import org.gotson.komga.interfaces.api.rest.dto.DedupReasonSeverity
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime

@Service
class DedupEligibilityPolicy(
  private val dedupRepository: DedupRepository,
  private val decisionRepository: DedupDecisionRepository,
  private val bookRepository: BookRepository,
  private val coverLifecycle: DedupCoverLifecycle,
  private val localStateLifecycle: DedupLocalStateLifecycle,
  private val physicalDeletionLifecycle: DedupPhysicalBookDeletionLifecycle,
  private val objectMapper: ObjectMapper,
) {
  companion object {
    const val RULE_VERSION = 4
  }

  fun evaluate(
    reviewCase: DedupReviewCase,
    keeperBookId: String? = reviewCase.suggestedKeeperBookId,
    removeBookIds: Set<String> = keeperBookId?.let { reviewCase.memberBookIds - it }.orEmpty(),
  ): DedupEligibilityReportDto {
    val blockers = mutableListOf<DedupEligibilityReasonDto>()
    val warnings = mutableListOf<DedupEligibilityReasonDto>()
    val passed = mutableListOf<DedupEligibilityReasonDto>()
    val books = reviewCase.memberBookIds.mapNotNull(bookRepository::findByIdOrNull).associateBy { it.id }
    val activeBySeries =
      bookRepository
        .findAllBySeriesIds(books.values.map { it.seriesId }.toSet())
        .filter { it.deletedDate == null }
        .groupBy { it.seriesId }
    val outOfScope =
      reviewCase.memberBookIds.filterTo(mutableSetOf()) { bookId ->
        val book = books[bookId]
        book == null || book.deletedDate != null || !book.url.path.endsWith(".cbz", true) || activeBySeries[book.seriesId].orEmpty().size != 1
      }
    if (outOfScope.isNotEmpty()) {
      blockers +=
        reason(
          "OUT_OF_SCOPE_MULTI_BOOK_SERIES",
          DedupReasonSeverity.BLOCKER,
          DedupAction.entries.toSet(),
          memberIds = outOfScope,
          actual = outOfScope.associateWith { books[it]?.let { book -> activeBySeries[book.seriesId].orEmpty().size } ?: 0 },
          threshold = 1,
          action = "VIEW_TASK",
        )
    } else {
      passed += reason("MVP_SCOPE_CURRENT", DedupReasonSeverity.PASSED, DedupAction.entries.toSet())
    }

    val protected = dedupRepository.findProtectedBookIds(reviewCase.memberBookIds)
    if (protected.isNotEmpty()) {
      blockers += reason("PROTECTED", DedupReasonSeverity.BLOCKER, DedupAction.entries.toSet(), memberIds = protected, action = "PROTECT_MEMBER")
    }
    if (decisionRepository.hasActiveDecisionForBooks(reviewCase.memberBookIds)) {
      blockers += reason("DELETION_IN_PROGRESS", DedupReasonSeverity.BLOCKER, DedupAction.entries.toSet(), action = "VIEW_TASK")
    }
    if (keeperBookId == null) {
      blockers += reason("NO_KEEPER", DedupReasonSeverity.BLOCKER, setOf(DedupAction.SUGGESTED), action = "SET_KEEPER")
    } else if (keeperBookId !in reviewCase.memberBookIds || keeperBookId in removeBookIds || removeBookIds.isEmpty() || removeBookIds.any { it !in reviewCase.memberBookIds }) {
      blockers += reason("PLAN_CHANGED", DedupReasonSeverity.BLOCKER, DedupAction.entries.toSet(), action = "SET_KEEPER")
    }

    val effectiveLosers = if (keeperBookId == null) reviewCase.memberBookIds else removeBookIds
    val identities = reviewCase.memberBookIds.associateWith(coverLifecycle::currentSourceIdentity)
    val relations =
      if (keeperBookId == null) {
        reviewCase.memberBookIds.toList().let { ids -> if (ids.size == 2) listOfNotNull(dedupRepository.findRelation(ids[0], ids[1])) else emptyList() }
      } else {
        effectiveLosers.mapNotNull { loser -> dedupRepository.findRelation(loser, keeperBookId) }
      }
    if ((keeperBookId != null && relations.size != effectiveLosers.size) || (keeperBookId == null && relations.isEmpty())) {
      blockers += reason("DIRECT_KEEPER_RELATION_MISSING", DedupReasonSeverity.BLOCKER, DedupAction.entries.toSet(), action = "RUN_DEEP_VERIFICATION")
    }

    relations.forEach { relation ->
      val generationMismatch = !relation.isCurrent(identities)
      if (relation.status != DedupRelationStatus.VERIFIED || generationMismatch) {
        blockers +=
          reason(
            "FEATURE_STALE",
            DedupReasonSeverity.BLOCKER,
            DedupAction.entries.toSet(),
            memberIds = setOf(relation.bookLowId, relation.bookHighId),
            actual = relation.status.name,
            threshold = DedupRelationStatus.VERIFIED.name,
            action = "REANALYZE_CASE",
          )
        return@forEach
      }
      val loser = keeperBookId?.let { setOf(relation.bookLowId, relation.bookHighId).singleOrNull { id -> id != it } }
      when (relation.type) {
        DedupRelationType.EXACT_FILE,
        DedupRelationType.EXACT_PAGE_SEQUENCE,
        -> Unit

        DedupRelationType.CONTAINED_IN ->
          if (loser == null || relation.containedBookId != loser || relation.containerBookId != keeperBookId) {
            blockers += reason("DIRECT_KEEPER_RELATION_MISSING", DedupReasonSeverity.BLOCKER, setOf(DedupAction.SUGGESTED), action = "SET_KEEPER")
          }

        DedupRelationType.SAME_EDITION_VARIANT ->
          blockers += reason("LOW_CONFIDENCE", DedupReasonSeverity.BLOCKER, setOf(DedupAction.SUGGESTED), action = "OPEN_PAGE_COMPARISON")

        DedupRelationType.NEAR_CONTAINED_IN ->
          addContentRisk("ANCILLARY_UNCONFIRMED", relation, blockers, warnings)

        DedupRelationType.PARTIAL_OVERLAP -> addContentRisk("PARTIAL_OVERLAP", relation, blockers, warnings)
        DedupRelationType.ALT_EDITION,
        DedupRelationType.EDITION_UNCERTAIN,
        -> addContentRisk("ALT_EDITION", relation, blockers, warnings)

        DedupRelationType.VISUALLY_SIMILAR -> addContentRisk("COVER_ONLY", relation, blockers, warnings, "RUN_DEEP_VERIFICATION")
        DedupRelationType.UNRELATED -> addContentRisk("NOT_CONTENT_DUPLICATE", relation, blockers, warnings)
      }
    }

    val stateSnapshots =
      effectiveLosers
        .mapNotNull { id -> runCatching { localStateLifecycle.snapshot(id) }.getOrNull() }
        .associateBy { it.bookId }
    stateSnapshots.values.forEach { snapshot ->
      snapshot.reasonCodes.forEach { code ->
        blockers +=
          reason(
            code,
            DedupReasonSeverity.BLOCKER,
            setOf(DedupAction.SUGGESTED),
            memberIds = setOf(snapshot.bookId),
            actual = snapshot.details[detailKey(code)],
            action = "SELECT_STATEFUL_COPY_AS_KEEPER",
          )
        warnings +=
          reason(
            code,
            DedupReasonSeverity.WARNING,
            setOf(DedupAction.MANUAL),
            confirmationRequired = true,
            memberIds = setOf(snapshot.bookId),
            actual = snapshot.details[detailKey(code)],
            action = "OPEN_PAGE_COMPARISON",
          )
      }
    }
    books.values.forEach { book ->
      val precheck = physicalDeletionLifecycle.precheck(book)
      when (precheck.status) {
        DedupFilePrecheckStatus.AVAILABLE -> Unit
        DedupFilePrecheckStatus.UNAVAILABLE ->
          blockers +=
            reason(
              "SOURCE_FILE_UNAVAILABLE",
              DedupReasonSeverity.BLOCKER,
              DedupAction.entries.toSet(),
              memberIds = setOf(book.id),
              actual = precheck.detail,
              action = "CHECK_SOURCE_FILE",
            )
        DedupFilePrecheckStatus.STAT_STALE ->
          blockers +=
            reason(
              "SOURCE_FILE_STAT_STALE",
              DedupReasonSeverity.BLOCKER,
              DedupAction.entries.toSet(),
              memberIds = setOf(book.id),
              actual =
                mapOf(
                  "databaseSize" to precheck.databaseSize,
                  "liveSize" to precheck.liveSize,
                  "databaseMtime" to precheck.databaseMtime,
                  "liveMtime" to precheck.liveMtime,
                ),
              action = "ANALYZE_BOOK",
            )
      }
    }
    if (stateSnapshots.values.none { it.reasonCodes.isNotEmpty() }) {
      passed += reason("NO_LOCAL_STATE", DedupReasonSeverity.PASSED, DedupAction.entries.toSet())
    }
    warnings += reason("EXTERNAL_STATE_NOT_VERIFIED", DedupReasonSeverity.WARNING, DedupAction.entries.toSet())
    warnings += reason("GORSE_STATE_UNKNOWN", DedupReasonSeverity.WARNING, DedupAction.entries.toSet())

    val stateRevision = stableHash(stateSnapshots.values.sortedBy { it.bookId }.joinToString("|") { "${it.bookId}:${it.revision}" })
    val planRevision =
      keeperBookId?.let {
        stableHash(
          buildString {
            append(it)
            append('|')
            append(removeBookIds.sorted().joinToString(","))
            append('|')
            append(relations.sortedBy { relation -> relation.id }.joinToString("|") { relation -> relation.planIdentity() })
          },
        )
      }
    return DedupEligibilityReportDto(
      suggestedPlanEligible = blockers.none { DedupAction.SUGGESTED in it.appliesTo },
      manualDeleteEligible = blockers.none { DedupAction.MANUAL in it.appliesTo },
      ruleVersion = RULE_VERSION,
      stateRevision = stateRevision,
      planRevision = planRevision,
      evaluatedAt = LocalDateTime.now(),
      blockers = blockers.distinctBy { Triple(it.code, it.appliesTo, it.memberIds) },
      warnings = warnings.distinctBy { Triple(it.code, it.appliesTo, it.memberIds) },
      passed = passed,
    )
  }

  private fun DedupRelation.isCurrent(identities: Map<String, org.gotson.komga.domain.model.DedupSourceIdentity?>): Boolean {
    val low = identities[bookLowId] ?: return false
    val high = identities[bookHighId] ?: return false
    if (lowContentGeneration != low.contentGeneration || highContentGeneration != high.contentGeneration) return false
    if (lowMetadataGeneration.isNotEmpty() && lowMetadataGeneration != low.metadataGeneration) return false
    if (highMetadataGeneration.isNotEmpty() && highMetadataGeneration != high.metadataGeneration) return false
    if (type == DedupRelationType.VISUALLY_SIMILAR) {
      if (lowCoverGeneration != low.coverGeneration || highCoverGeneration != high.coverGeneration) return false
    }
    return true
  }

  private fun addContentRisk(
    code: String,
    relation: DedupRelation,
    blockers: MutableList<DedupEligibilityReasonDto>,
    warnings: MutableList<DedupEligibilityReasonDto>,
    action: String = "OPEN_PAGE_COMPARISON",
  ) {
    val coverOnly = code == "COVER_ONLY"
    val ranges =
      runCatching {
        val evidence = objectMapper.readTree(relation.evidenceJson)
        listOf("leftUnmatchedRanges", "rightUnmatchedRanges").flatMap { name -> evidence.path(name).map { it.asText() } }
      }.getOrDefault(emptyList())
    blockers +=
      reason(
        code,
        DedupReasonSeverity.BLOCKER,
        setOf(DedupAction.SUGGESTED),
        memberIds = setOf(relation.bookLowId, relation.bookHighId),
        actual = if (coverOnly) null else relation.unmatchedPrefixCount.orZero() + relation.unmatchedSuffixCount.orZero() + relation.unmatchedInternalCount.orZero(),
        threshold = if (coverOnly) null else 0,
        pageRanges = ranges,
        action = action,
      )
    warnings +=
      reason(
        code,
        DedupReasonSeverity.WARNING,
        setOf(DedupAction.MANUAL),
        confirmationRequired = true,
        memberIds = setOf(relation.bookLowId, relation.bookHighId),
        actual = relation.type.name,
        pageRanges = ranges,
        action = action,
      )
  }

  private fun DedupRelation.planIdentity(): String = "$id:$bookLowId:$bookHighId:$lowContentGeneration:$highContentGeneration:$type:$containedBookId:$containerBookId:$classifierRuleVersion"

  private fun detailKey(code: String): String =
    when (code) {
      "READ_PROGRESS_PRESENT" -> "readProgress"
      "READLIST_PRESENT" -> "readLists"
      "COLLECTION_PRESENT" -> "collections"
      else -> "bookMetadataLocked"
    }

  private fun reason(
    code: String,
    severity: DedupReasonSeverity,
    appliesTo: Set<DedupAction>,
    confirmationRequired: Boolean = false,
    memberIds: Set<String> = emptySet(),
    actual: Any? = null,
    threshold: Any? = null,
    pageRanges: List<String> = emptyList(),
    action: String? = null,
  ) = DedupEligibilityReasonDto(
    code = code,
    severity = severity,
    appliesTo = appliesTo,
    confirmationRequired = confirmationRequired,
    scope = if (memberIds.isEmpty()) "CASE" else "BOOK",
    memberIds = memberIds,
    messageKey = "dedup.eligibility.$code",
    actual = actual,
    threshold = threshold,
    pageRanges = pageRanges,
    action = action,
  )

  private fun Int?.orZero(): Int = this ?: 0

  private fun stableHash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}
