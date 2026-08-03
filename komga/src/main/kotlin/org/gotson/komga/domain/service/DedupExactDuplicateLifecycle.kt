package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.DedupReviewCaseCandidate
import org.gotson.komga.domain.model.DedupReviewCaseOrigin
import org.gotson.komga.domain.model.ExactDuplicateBook
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.ExactDuplicateBookRepository
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime

@Service
class DedupExactDuplicateLifecycle(
  private val exactDuplicateBookRepository: ExactDuplicateBookRepository,
  private val dedupRepository: DedupRepository,
  private val objectMapper: ObjectMapper,
) {
  fun reconcileLibrary(
    libraryId: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Int {
    val cases =
      exactDuplicateBookRepository
        .findAllExactDuplicates(libraryId = libraryId, includeDeleted = false)
        .filter { it.url.substringBefore('?').endsWith(".cbz", ignoreCase = true) }
        .groupBy { it.fileHash to it.fileSize }
        .values
        .filter { it.size > 1 }
        .map { books -> books.toReviewCase(now) }

    dedupRepository.replaceReviewCases(
      libraryId = libraryId,
      origin = DedupReviewCaseOrigin.EXACT_FILE,
      candidates = cases,
      now = now,
    )
    return cases.size
  }

  private fun List<ExactDuplicateBook>.toReviewCase(now: LocalDateTime): DedupReviewCaseCandidate {
    val sorted = sortedBy { it.id }
    val identity = "${first().libraryId}|${first().fileHash}|${first().fileSize}"
    val relations =
      sorted.flatMapIndexed { index, left ->
        sorted.drop(index + 1).map { right ->
          DedupRelation(
            id = "exact-relation-${stableHash("${left.id}|${right.id}")}",
            libraryId = left.libraryId,
            bookLowId = left.id,
            bookHighId = right.id,
            lowContentGeneration = left.contentGeneration(),
            highContentGeneration = right.contentGeneration(),
            type = DedupRelationType.EXACT_FILE,
            evidenceJson =
              objectMapper.writeValueAsString(
                mapOf(
                  "fileHash" to left.fileHash,
                  "fileSize" to left.fileSize,
                  "identity" to "FILE_HASH_AND_SIZE",
                ),
              ),
            createdDate = now,
            lastModifiedDate = now,
          )
        }
      }

    return DedupReviewCaseCandidate(
      id = "exact-case-${stableHash(identity)}",
      libraryId = first().libraryId,
      origin = DedupReviewCaseOrigin.EXACT_FILE,
      memberBookIds = sorted.map { it.id }.toSet(),
      relations = relations,
    )
  }

  private fun ExactDuplicateBook.contentGeneration(): String = stableHash("$fileHash|$fileSize|$fileLastModified")

  private fun stableHash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
      .take(32)
}
