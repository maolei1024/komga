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
  fun reconcileLibrary(
    libraryId: String,
    now: LocalDateTime = LocalDateTime.now(),
  ): Int {
    val relations =
      exactDuplicateBookRepository
        .findAllExactDuplicates(libraryId = libraryId, includeDeleted = false)
        .filter { it.url.substringBefore('?').endsWith(".cbz", ignoreCase = true) }
        .groupBy { it.fileHash to it.fileSize }
        .values
        .filter { it.size > 1 }
        .flatMap { books -> books.toRelations(now) }

    dedupRepository.replaceExactRelations(libraryId, relations, now)
    return relations.size
  }

  private fun List<ExactDuplicateBook>.toRelations(now: LocalDateTime): List<DedupRelation> {
    val sorted = sortedBy { it.id }
    return sorted.flatMapIndexed { index, left ->
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
  }

  private fun ExactDuplicateBook.contentGeneration(): String = coverLifecycle.currentContentGeneration(id) ?: dedupContentGeneration(fileSize, null)

  private fun stableHash(value: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(value.toByteArray(StandardCharsets.UTF_8))
      .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
      .take(32)
}
