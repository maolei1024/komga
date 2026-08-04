package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.DedupFeatureState
import org.gotson.komga.domain.model.DedupPageFeature
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.MediaRepository
import org.gotson.komga.infrastructure.dedup.CoverPerceptualHasher
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class DedupDeepVerificationLifecycle(
  private val dedupRepository: DedupRepository,
  private val bookRepository: BookRepository,
  private val mediaRepository: MediaRepository,
  private val bookLifecycle: BookLifecycle,
  private val bookAnalyzer: BookAnalyzer,
  private val coverHasher: CoverPerceptualHasher,
  private val coverLifecycle: DedupCoverLifecycle,
  private val aligner: PageSequenceAligner,
  private val objectMapper: ObjectMapper,
) {
  companion object {
    const val PAGE_FEATURE_SCHEMA_VERSION = 1
    const val CLASSIFIER_RULE_VERSION = 2
  }

  fun verifyRelation(
    firstBookId: String,
    secondBookId: String,
  ) {
    val memberIds = listOf(firstBookId, secondBookId).sorted()
    require(memberIds[0] != memberIds[1]) { "Deep verification requires two different Books" }
    val lowIdentity = requireNotNull(coverLifecycle.currentSourceIdentity(memberIds[0]))
    val highIdentity = requireNotNull(coverLifecycle.currentSourceIdentity(memberIds[1]))
    require(lowIdentity.libraryId == highIdentity.libraryId) { "Deep verification Books must be in one Library" }
    val currentRelation = dedupRepository.findRelation(memberIds[0], memberIds[1])
    if (currentRelation?.type == DedupRelationType.EXACT_FILE) return
    val settings = requireNotNull(dedupRepository.findLibrarySettings(lowIdentity.libraryId))
    val deadline = LocalDateTime.now().plusSeconds(settings.maxDurationSeconds.toLong())
    val left = loadPageFeatures(memberIds[0], deadline)
    val right = loadPageFeatures(memberIds[1], deadline)
    val alignment = aligner.align(memberIds[0], left, memberIds[1], right)
    val lowGeneration = requireNotNull(coverLifecycle.currentContentGeneration(memberIds[0]))
    val highGeneration = requireNotNull(coverLifecycle.currentContentGeneration(memberIds[1]))
    val unmatched =
      when (alignment.containedBookId) {
        memberIds[0] -> alignment.unmatchedLeft
        memberIds[1] -> alignment.unmatchedRight
        else -> alignment.unmatchedLeft
      }
    val now = LocalDateTime.now()
    val relation =
      DedupRelation(
        id = currentRelation?.id ?: "verified-${memberIds[0]}-${memberIds[1]}",
        libraryId = lowIdentity.libraryId,
        bookLowId = memberIds[0],
        bookHighId = memberIds[1],
        lowContentGeneration = lowGeneration,
        highContentGeneration = highGeneration,
        lowCoverGeneration = currentRelation?.lowCoverGeneration.orEmpty(),
        highCoverGeneration = currentRelation?.highCoverGeneration.orEmpty(),
        lowMetadataGeneration = currentRelation?.lowMetadataGeneration.orEmpty(),
        highMetadataGeneration = currentRelation?.highMetadataGeneration.orEmpty(),
        type = alignment.relationType,
        coverDistance = currentRelation?.coverDistance,
        containedBookId = alignment.containedBookId,
        containerBookId = alignment.containerBookId,
        coverageLeft = alignment.coverageLeft,
        coverageRight = alignment.coverageRight,
        orderConsistency = 1.0,
        longestMatchedRun = alignment.longestMatchedRun,
        unmatchedPrefixCount = unmatched.prefixCount,
        unmatchedSuffixCount = unmatched.suffixCount,
        unmatchedInternalCount = unmatched.internalCount,
        evidenceJson =
          objectMapper.writeValueAsString(
            mapOf(
              "leftUnmatchedRanges" to alignment.unmatchedLeft.ranges,
              "rightUnmatchedRanges" to alignment.unmatchedRight.ranges,
              "matchedPages" to alignment.matches.size,
              "exactMatches" to alignment.matches.count { it.exact },
              "matches" to
                alignment.matches.map {
                  mapOf(
                    "leftPage" to it.leftPage,
                    "rightPage" to it.rightPage,
                    "exact" to it.exact,
                  )
                },
              "ancillaryClassification" to "UNCONFIRMED",
            ),
          ),
        featureSchemaVersion = PAGE_FEATURE_SCHEMA_VERSION,
        classifierRuleVersion = CLASSIFIER_RULE_VERSION,
        createdDate = currentRelation?.createdDate ?: now,
        lastModifiedDate = now,
      )
    dedupRepository.saveRelation(relation)
  }

  private fun loadPageFeatures(
    bookId: String,
    deadline: LocalDateTime,
  ): List<DedupPageFeature> {
    val book = requireNotNull(bookRepository.findByIdOrNull(bookId))
    val generation = requireNotNull(coverLifecycle.currentContentGeneration(bookId))
    val media = mediaRepository.findById(bookId)
    val existing = dedupRepository.findPageFeatures(bookId, generation, PAGE_FEATURE_SCHEMA_VERSION)
    if (existing.size == media.pageCount && existing.map { it.pageNumber } == (1..media.pageCount).toList()) return existing

    val features =
      (1..media.pageCount).map { pageNumber ->
        check(LocalDateTime.now().isBefore(deadline)) { "Deep verification exceeded the configured run budget" }
        val content = bookLifecycle.getBookPage(book, pageNumber).bytes
        val page = media.pages[pageNumber - 1]
        val perceptual = coverHasher.hash(content)
        DedupPageFeature(
          bookId = bookId,
          sourceContentGeneration = generation,
          featureSchemaVersion = PAGE_FEATURE_SCHEMA_VERSION,
          pageNumber = pageNumber,
          exactHash = bookAnalyzer.hashPage(page, content),
          perceptualHash = perceptual.hash,
          quality = perceptual.quality,
        )
      }
    check(coverLifecycle.currentContentGeneration(bookId) == generation) { "Book changed during page analysis" }
    dedupRepository.replacePageFeatures(bookId, generation, PAGE_FEATURE_SCHEMA_VERSION, features)
    dedupRepository.findFeature(bookId)?.let { feature ->
      dedupRepository.saveFeature(
        feature.copy(
          pageState = DedupFeatureState.READY,
          pageCount = media.pageCount,
          analyzedDate = LocalDateTime.now(),
          lastModifiedDate = LocalDateTime.now(),
        ),
      )
    }
    return features
  }
}
