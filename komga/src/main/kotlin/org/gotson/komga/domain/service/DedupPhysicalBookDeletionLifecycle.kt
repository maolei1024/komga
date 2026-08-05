package org.gotson.komga.domain.service

import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupDeletionResultCode
import org.gotson.komga.infrastructure.hash.Hasher
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.exists

data class DedupStrongFileIdentity(
  val path: String,
  val size: Long,
  val archiveHash: String,
) {
  fun matches(other: DedupStrongFileIdentity): Boolean =
    path == other.path &&
      size == other.size &&
      archiveHash == other.archiveHash
}

data class DedupPhysicalDeletionResult(
  val code: DedupDeletionResultCode,
  val pathAbsent: Boolean,
  val databaseSoftDeleted: Boolean,
  val detail: String? = null,
)

data class DedupFilePrecheck(
  val status: DedupFilePrecheckStatus,
  val path: String,
  val databaseSize: Long,
  val liveSize: Long? = null,
  val detail: String? = null,
)

enum class DedupFilePrecheckStatus {
  AVAILABLE,
  UNAVAILABLE,
  STAT_STALE,
}

@Service
class DedupPhysicalBookDeletionLifecycle(
  private val hasher: Hasher,
  private val bookLifecycle: BookLifecycle,
) {
  fun captureStrongIdentity(
    book: Book,
    requireDatabaseStat: Boolean = true,
  ): DedupStrongFileIdentity {
    val path = book.path.toAbsolutePath().normalize()
    val before = readStableAttributes(path)
    check(Files.isReadable(path)) { "Book path is not readable" }
    val hash = hasher.computeHash(path)
    val after = readStableAttributes(path)
    check(before.sameFileVersion(after)) { "Book changed while its full archive hash was being computed" }
    val identity = DedupStrongFileIdentity(path.toString(), after.size(), hash)
    if (requireDatabaseStat) {
      check(book.fileSize == identity.size) { "Live file size no longer matches Komga" }
    }
    return identity
  }

  fun precheck(book: Book): DedupFilePrecheck {
    val path = book.path.toAbsolutePath().normalize()
    if (!path.isCbz()) return unavailable(book, path, "Expected path is not a CBZ archive")
    if (!Files.isRegularFile(path)) return unavailable(book, path, "Expected path is not a regular file")
    if (!Files.isReadable(path)) return unavailable(book, path, "Book path is not readable")
    if (!isWritable(path)) return unavailable(book, path, "Book path is not writable")
    val attributes = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrElse { return unavailable(book, path, it.message ?: "Book stat is unavailable") }
    return if (book.fileSize <= 0 || book.fileSize != attributes.size()) {
      DedupFilePrecheck(
        status = DedupFilePrecheckStatus.STAT_STALE,
        path = path.toString(),
        databaseSize = book.fileSize,
        liveSize = attributes.size(),
        detail = "Live file size no longer matches Komga",
      )
    } else {
      DedupFilePrecheck(
        status = DedupFilePrecheckStatus.AVAILABLE,
        path = path.toString(),
        databaseSize = book.fileSize,
        liveSize = attributes.size(),
      )
    }
  }

  fun deleteVerifiedBook(
    book: Book,
    expected: DedupStrongFileIdentity,
    onPathAbsent: () -> Unit = {},
  ): DedupPhysicalDeletionResult {
    val path = book.path.toAbsolutePath().normalize()
    if (path.toString() != expected.path) return conflict(DedupDeletionResultCode.GENERATION_MISMATCH, "Book path changed")
    if (!path.exists()) return conflict(DedupDeletionResultCode.PATH_MISSING_UNCONFIRMED, "Expected path is absent")
    if (!isWritable(path)) return conflict(DedupDeletionResultCode.NOT_WRITABLE, "Expected path is not writable")

    val current =
      try {
        captureStrongIdentity(book, requireDatabaseStat = false)
      } catch (exception: Exception) {
        return conflict(DedupDeletionResultCode.GENERATION_MISMATCH, exception.message)
      }
    if (!current.matches(expected)) return conflict(DedupDeletionResultCode.GENERATION_MISMATCH, "Live path, size, or archive hash changed")
    val justBeforeDelete = readStableAttributes(path)
    if (justBeforeDelete.size() != expected.size) {
      return conflict(DedupDeletionResultCode.GENERATION_MISMATCH, "File changed after final hash validation")
    }

    return try {
      Files.delete(path)
      if (Files.exists(path)) return conflict(DedupDeletionResultCode.DELETE_FAILED, "Path still exists after unlink")
      onPathAbsent()
      bookLifecycle.softDeleteMany(listOf(book))
      DedupPhysicalDeletionResult(DedupDeletionResultCode.DELETED, pathAbsent = true, databaseSoftDeleted = true)
    } catch (exception: Exception) {
      val pathAbsent = Files.notExists(path)
      DedupPhysicalDeletionResult(
        if (pathAbsent) DedupDeletionResultCode.KOMGA_NOT_SAVED else DedupDeletionResultCode.DELETE_FAILED,
        pathAbsent = pathAbsent,
        databaseSoftDeleted = false,
        detail = exception.message?.take(500),
      )
    }
  }

  fun confirmPathAbsentAndSoftDelete(book: Book): DedupPhysicalDeletionResult {
    val path = book.path.toAbsolutePath().normalize()
    if (!Files.notExists(path)) return conflict(DedupDeletionResultCode.REAPPEARED_DIFFERENT_HASH, "Expected path reappeared or cannot be confirmed absent")
    return try {
      if (book.deletedDate == null) bookLifecycle.softDeleteMany(listOf(book))
      DedupPhysicalDeletionResult(DedupDeletionResultCode.ALREADY_DELETED_BY_THIS_RESOLUTION, pathAbsent = true, databaseSoftDeleted = true)
    } catch (exception: Exception) {
      DedupPhysicalDeletionResult(DedupDeletionResultCode.KOMGA_NOT_SAVED, pathAbsent = true, databaseSoftDeleted = false, detail = exception.message?.take(500))
    }
  }

  private fun readStableAttributes(path: Path): BasicFileAttributes {
    check(path.isCbz()) { "Expected path is not a CBZ archive" }
    check(Files.isRegularFile(path)) { "Expected path is not a regular file" }
    return Files.readAttributes(path, BasicFileAttributes::class.java)
  }

  private fun Path.isCbz(): Boolean = fileName.toString().endsWith(".cbz", ignoreCase = true)

  private fun unavailable(
    book: Book,
    path: Path,
    detail: String,
  ) = DedupFilePrecheck(
    status = DedupFilePrecheckStatus.UNAVAILABLE,
    path = path.toString(),
    databaseSize = book.fileSize,
    detail = detail,
  )

  private fun isWritable(path: Path): Boolean {
    if (!Files.isWritable(path)) return false
    return runCatching {
      Files.getPosixFilePermissions(path).any {
        it in setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)
      }
    }.getOrDefault(true)
  }

  private fun BasicFileAttributes.sameFileVersion(other: BasicFileAttributes): Boolean = size() == other.size() && fileKey() == other.fileKey()

  private fun conflict(
    code: DedupDeletionResultCode,
    detail: String?,
  ) = DedupPhysicalDeletionResult(code, pathAbsent = false, databaseSoftDeleted = false, detail = detail)
}
