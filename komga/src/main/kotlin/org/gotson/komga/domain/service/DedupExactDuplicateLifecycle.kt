package org.gotson.komga.domain.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.DedupRelation
import org.gotson.komga.domain.model.DedupRelationType
import org.gotson.komga.domain.model.ExactDuplicateBook
import org.gotson.komga.domain.model.dedupContentGeneration
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
  private val coverLifecycle: DedupCoverLifecycle,
  private val objectMapper: ObjectMapper,
) {
  fun refreshForBook(
    bookId: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Int {
    val books =
      exactDuplicateBookRepository
        .findExactDuplicatesForBook(bookId)
        .filter { it.url.substringBefore('?').endsWith(".cbz", ignoreCase = true) }
    val target = books.firstOrNull { it.id == bookId }
    val relations =
      if (target == null) {
        emptyList()
      } else {
        books
          .filter { it.id != bookId }
          .map { other -> listOf(target, other).sortedBy { it.id }.toRelation(now) }
      }

    dedupRepository.replaceExactRelationsForBook(bookId, relations)
    return relations.size
  }

  private fun List<ExactDuplicateBook>.toRelation(now: LocalDateTime): DedupRelation {
    val (left, right) = this
    return DedupRelation(
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

  private fun ExactDuplicateBook.contentGeneration(): String = coverLifecycle.currentContentGeneration(id) ?: dedupContentGeneration(fileSize, null, fileHash)

  private fun stableHash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
      .take(32)
}
