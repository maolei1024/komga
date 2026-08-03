package org.gotson.komga.domain.service

import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupDeletionResultCode
import org.gotson.komga.infrastructure.hash.Hasher
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDateTime
import java.time.ZoneId

class DedupPhysicalBookDeletionLifecycleTest {
  @TempDir
  lateinit var directory: Path

  private val hasher = Hasher()
  private val bookLifecycle = mockk<BookLifecycle>()
  private val lifecycle = DedupPhysicalBookDeletionLifecycle(hasher, bookLifecycle)

  @BeforeEach
  fun clearMockCalls() {
    clearMocks(bookLifecycle)
  }

  @Test
  fun `dedup deletes only the explicitly verified CBZ and leaves companion artifacts`() {
    val cbz = Files.write(directory.resolve("book.cbz"), "verified archive".toByteArray())
    val pdf = Files.write(directory.resolve("companion.pdf"), "keep pdf".toByteArray())
    val sidecar = Files.write(directory.resolve("cover.jpg"), "keep sidecar".toByteArray())
    val book = bookFor(cbz)
    every { bookLifecycle.softDeleteMany(listOf(book)) } just Runs

    val expected = lifecycle.captureStrongIdentity(book)
    val result = lifecycle.deleteVerifiedBook(book, expected)

    assertThat(result.code).isEqualTo(DedupDeletionResultCode.DELETED)
    assertThat(cbz).doesNotExist()
    assertThat(pdf).exists()
    assertThat(sidecar).exists()
    assertThat(directory).exists()
    verify(exactly = 1) { bookLifecycle.softDeleteMany(listOf(book)) }
  }

  @Test
  fun `a file changed after approval conflicts and is never unlinked`() {
    val cbz = Files.write(directory.resolve("changed.cbz"), "first version".toByteArray())
    val book = bookFor(cbz)
    val expected = lifecycle.captureStrongIdentity(book)
    Files.write(cbz, "different version".toByteArray())
    Files.setLastModifiedTime(cbz, FileTime.fromMillis(System.currentTimeMillis() + 2_000))

    val result = lifecycle.deleteVerifiedBook(book, expected)

    assertThat(result.code).isEqualTo(DedupDeletionResultCode.GENERATION_MISMATCH)
    assertThat(cbz).exists()
    verify(exactly = 0) { bookLifecycle.softDeleteMany(any()) }
  }

  @Test
  fun `an absent expected path is not reported as a successful deletion`() {
    val cbz = Files.write(directory.resolve("missing.cbz"), "archive".toByteArray())
    val book = bookFor(cbz)
    val expected = lifecycle.captureStrongIdentity(book)
    Files.delete(cbz)

    val result = lifecycle.deleteVerifiedBook(book, expected)

    assertThat(result.code).isEqualTo(DedupDeletionResultCode.PATH_MISSING_UNCONFIRMED)
    verify(exactly = 0) { bookLifecycle.softDeleteMany(any()) }
  }

  @Test
  fun `a read-only archive is not unlinked even when tests run as a privileged user`() {
    val cbz = Files.write(directory.resolve("readonly.cbz"), "archive".toByteArray())
    val book = bookFor(cbz)
    val expected = lifecycle.captureStrongIdentity(book)
    val original = Files.getPosixFilePermissions(cbz)
    Files.setPosixFilePermissions(cbz, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ))
    try {
      val result = lifecycle.deleteVerifiedBook(book, expected)

      assertThat(result.code).isEqualTo(DedupDeletionResultCode.NOT_WRITABLE)
      assertThat(cbz).exists()
      verify(exactly = 0) { bookLifecycle.softDeleteMany(any()) }
    } finally {
      Files.setPosixFilePermissions(cbz, original)
    }
  }

  private fun bookFor(path: Path): Book {
    val modified = LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault())
    return Book(
      name = path.fileName.toString(),
      url = path.toUri().toURL(),
      fileLastModified = modified,
      fileSize = Files.size(path),
      fileHash = hasher.computeHash(path),
      seriesId = "series",
      libraryId = "library",
      oneshot = true,
    )
  }
}
