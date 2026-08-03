package org.gotson.komga.domain.service

import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupDeletionResultCode
import org.gotson.komga.infrastructure.hash.Hasher
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.io.path.exists

data class DedupStrongFileIdentity(
  val path: String,
  val size: Long,
  val mtime: LocalDateTime,
  val archiveHash: String,
) {
  fun matches(other: DedupStrongFileIdentity): Boolean =
    path == other.path &&
      size == other.size &&
      mtime.truncatedTo(ChronoUnit.MILLIS).isEqual(other.mtime.truncatedTo(ChronoUnit.MILLIS)) &&
      archiveHash == other.archiveHash
}

data class DedupPhysicalDeletionResult(
  val code: DedupDeletionResultCode,
  val pathAbsent: Boolean,
  val databaseSoftDeleted: Boolean,
  val detail: String? = null,
)

@Service
class DedupPhysicalBookDeletionLifecycle(
  private val hasher: Hasher,
  private val bookLifecycle: BookLifecycle,
) {
  fun captureStrongIdentity(
    book: Book,
    requireDatabaseIdentity: Boolean = true,
  ): DedupStrongFileIdentity {
    val path = book.path.toAbsolutePath().normalize()
    val before = readStableAttributes(path)
    check(Files.isReadable(path)) { "Book path is not readable" }
    val hash = hasher.computeHash(path)
    val after = readStableAttributes(path)
    check(before.sameFileVersion(after)) { "Book changed while its full archive hash was being computed" }
    val identity = DedupStrongFileIdentity(path.toString(), after.size(), after.lastModified(), hash)
    if (requireDatabaseIdentity) {
      check(book.fileSize == identity.size) { "Live file size no longer matches Komga" }
      check(book.fileLastModified.sameStoredTime(identity.mtime)) { "Live file mtime no longer matches Komga" }
      check(book.fileHash.isNotBlank() && book.fileHash == identity.archiveHash) { "Live archive hash no longer matches Komga" }
    }
    return identity
  }

  fun deleteVerifiedBook(
    book: Book,
    expected: DedupStrongFileIdentity,
  ): DedupPhysicalDeletionResult {
    val path = book.path.toAbsolutePath().normalize()
    if (path.toString() != expected.path) return conflict(DedupDeletionResultCode.GENERATION_MISMATCH, "Book path changed")
    if (!path.exists()) return conflict(DedupDeletionResultCode.PATH_MISSING_UNCONFIRMED, "Expected path is absent")
    if (!isWritable(path)) return conflict(DedupDeletionResultCode.NOT_WRITABLE, "Expected path is not writable")

    val current =
      try {
        captureStrongIdentity(book, requireDatabaseIdentity = false)
      } catch (exception: Exception) {
        return conflict(DedupDeletionResultCode.GENERATION_MISMATCH, exception.message)
      }
    if (!current.matches(expected)) return conflict(DedupDeletionResultCode.GENERATION_MISMATCH, "Live path, size, mtime, or archive hash changed")
    val justBeforeDelete = readStableAttributes(path)
    if (justBeforeDelete.size() != expected.size || !justBeforeDelete.lastModified().sameStoredTime(expected.mtime)) {
      return conflict(DedupDeletionResultCode.GENERATION_MISMATCH, "File changed after final hash validation")
    }

    return try {
      Files.delete(path)
      if (Files.exists(path)) return conflict(DedupDeletionResultCode.DELETE_FAILED, "Path still exists after unlink")
      bookLifecycle.softDeleteMany(listOf(book))
      DedupPhysicalDeletionResult(DedupDeletionResultCode.DELETED, pathAbsent = true, databaseSoftDeleted = true)
    } catch (exception: Exception) {
      DedupPhysicalDeletionResult(
        DedupDeletionResultCode.DELETE_FAILED,
        pathAbsent = Files.notExists(path),
        databaseSoftDeleted = false,
        detail = exception.message?.take(500),
      )
    }
  }

  private fun readStableAttributes(path: Path): BasicFileAttributes {
    check(Files.isRegularFile(path)) { "Expected path is not a regular file" }
    return Files.readAttributes(path, BasicFileAttributes::class.java)
  }

  private fun isWritable(path: Path): Boolean {
    if (!Files.isWritable(path)) return false
    return runCatching {
      Files.getPosixFilePermissions(path).any {
        it in setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)
      }
    }.getOrDefault(true)
  }

  private fun BasicFileAttributes.sameFileVersion(other: BasicFileAttributes): Boolean = size() == other.size() && lastModifiedTime() == other.lastModifiedTime() && fileKey() == other.fileKey()

  private fun BasicFileAttributes.lastModified(): LocalDateTime = LocalDateTime.ofInstant(lastModifiedTime().toInstant(), ZoneId.systemDefault())

  private fun LocalDateTime.sameStoredTime(other: LocalDateTime): Boolean = truncatedTo(ChronoUnit.MILLIS).isEqual(other.truncatedTo(ChronoUnit.MILLIS))

  private fun conflict(
    code: DedupDeletionResultCode,
    detail: String?,
  ) = DedupPhysicalDeletionResult(code, pathAbsent = false, databaseSoftDeleted = false, detail = detail)
}
