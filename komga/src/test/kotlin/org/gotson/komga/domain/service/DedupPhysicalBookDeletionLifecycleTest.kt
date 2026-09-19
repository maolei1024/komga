package org.gotson.komga.domain.service

import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.domain.model.Book
import org.gotson.komga.domain.model.DedupDeletionResultCode
import org.gotson.komga.infrastructure.hash.Hasher
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributeView
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
    val cbz = Files.write(directory.resolve("changed.cbz"), "first".toByteArray())
    val book = bookFor(cbz)
    val expected = lifecycle.captureStrongIdentity(book)
    Files.write(cbz, "other".toByteArray())
    Files.setLastModifiedTime(cbz, FileTime.fromMillis(System.currentTimeMillis() + 2_000))

    val result = lifecycle.deleteVerifiedBook(book, expected)

    assertThat(result.code).isEqualTo(DedupDeletionResultCode.GENERATION_MISMATCH)
    assertThat(cbz).exists()
    verify(exactly = 0) { bookLifecycle.softDeleteMany(any()) }
  }

  @Test
  fun `blank or stale Komga hash does not block a stable live archive identity`() {
    val cbz = Files.write(directory.resolve("no-database-hash.cbz"), "archive bytes".toByteArray())
    val book = bookFor(cbz).copy(fileHash = "")

    val identity = lifecycle.captureStrongIdentity(book)
    val identityWithStaleHash = lifecycle.captureStrongIdentity(book.copy(fileHash = "stale-database-hash"))

    assertThat(identity.archiveHash).isEqualTo(hasher.computeHash(cbz))
    assertThat(identityWithStaleHash).isEqualTo(identity)
    assertThat(lifecycle.precheck(book).status).isEqualTo(DedupFilePrecheckStatus.AVAILABLE)
  }

  @Test
  fun `mtime drift is ignored by precheck identity and deletion`() {
    val cbz = Files.write(directory.resolve("mtime-drift.cbz"), "stable archive".toByteArray())
    val book = bookFor(cbz)
    val expected = lifecycle.captureStrongIdentity(book)
    Files.setLastModifiedTime(cbz, FileTime.fromMillis(System.currentTimeMillis() + 60_000))
    every { bookLifecycle.softDeleteMany(listOf(book)) } just Runs

    assertThat(lifecycle.precheck(book).status).isEqualTo(DedupFilePrecheckStatus.AVAILABLE)
    assertThat(lifecycle.captureStrongIdentity(book)).isEqualTo(expected)
    assertThat(lifecycle.deleteVerifiedBook(book, expected).code).isEqualTo(DedupDeletionResultCode.DELETED)
  }

  @Test
  fun `mtime drift during full hash capture is ignored`() {
    val cbz = Files.write(directory.resolve("mtime-drift-during-hash.cbz"), "stable archive".toByteArray())
    val book = bookFor(cbz)
    val mutatingHasher = mockk<Hasher>()
    every { mutatingHasher.computeHash(cbz) } answers {
      Files.setLastModifiedTime(cbz, FileTime.fromMillis(System.currentTimeMillis() + 60_000))
      "stable-hash"
    }

    assertThat(DedupPhysicalBookDeletionLifecycle(mutatingHasher, bookLifecycle).captureStrongIdentity(book).archiveHash)
      .isEqualTo("stable-hash")
  }

  @Test
  fun `a non CBZ source remains ineligible for strong identity`() {
    val zip = Files.write(directory.resolve("book.zip"), "archive bytes".toByteArray())
    val book = bookFor(zip)

    assertThat(lifecycle.precheck(book).status).isEqualTo(DedupFilePrecheckStatus.UNAVAILABLE)
    assertThatThrownBy { lifecycle.captureStrongIdentity(book) }
      .hasMessageContaining("not a CBZ archive")
  }

  @Test
  fun `a file changing during full hash capture is rejected`() {
    val cbz = Files.write(directory.resolve("changing-during-hash.cbz"), "first".toByteArray())
    val book = bookFor(cbz)
    val mutatingHasher = mockk<Hasher>()
    every { mutatingHasher.computeHash(cbz) } answers {
      Files.write(cbz, "changed while hashing".toByteArray())
      "unstable-hash"
    }

    assertThatThrownBy { DedupPhysicalBookDeletionLifecycle(mutatingHasher, bookLifecycle).captureStrongIdentity(book) }
      .hasMessageContaining("changed while its full archive hash")
  }

  @Test
  fun `a same-size replacement during full hash capture is rejected`() {
    val cbz = Files.write(directory.resolve("replaced-during-hash.cbz"), "first".toByteArray())
    val book = bookFor(cbz)
    val mutatingHasher = mockk<Hasher>()
    every { mutatingHasher.computeHash(cbz) } answers {
      val hash = hasher.computeHash(cbz)
      val replacement = Files.write(directory.resolve("replacement.cbz"), "other".toByteArray())
      Files.move(replacement, cbz, StandardCopyOption.REPLACE_EXISTING)
      hash
    }

    assertThatThrownBy { DedupPhysicalBookDeletionLifecycle(mutatingHasher, bookLifecycle).captureStrongIdentity(book) }
      .hasMessageContaining("changed while its full archive hash")
  }

  @Test
  fun `stale Komga stat is distinguished from an unavailable source file`() {
    val cbz = Files.write(directory.resolve("stale-stat.cbz"), "archive bytes".toByteArray())
    val stale = bookFor(cbz).copy(fileSize = Files.size(cbz) + 1)

    assertThat(lifecycle.precheck(stale).status).isEqualTo(DedupFilePrecheckStatus.STAT_STALE)
    assertThatThrownBy { lifecycle.captureStrongIdentity(stale) }
      .hasMessageContaining("size no longer matches Komga")
    assertThat(lifecycle.precheck(stale.copy(url = directory.resolve("missing.cbz").toUri().toURL())).status)
      .isEqualTo(DedupFilePrecheckStatus.UNAVAILABLE)
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
    assumeTrue(Files.getFileStore(cbz).supportsFileAttributeView(PosixFileAttributeView::class.java))
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

  @Test
  @EnabledOnOs(OS.WINDOWS)
  fun `a DOS read-only archive is not unlinked`() {
    val cbz = Files.write(directory.resolve("dos-readonly.cbz"), "archive".toByteArray())
    val book = bookFor(cbz)
    val expected = lifecycle.captureStrongIdentity(book)
    val attributes = Files.getFileAttributeView(cbz, DosFileAttributeView::class.java)
    val original = attributes.readAttributes().isReadOnly
    attributes.setReadOnly(true)
    try {
      assertThat(lifecycle.precheck(book).status).isEqualTo(DedupFilePrecheckStatus.UNAVAILABLE)
      assertThat(lifecycle.deleteVerifiedBook(book, expected).code).isEqualTo(DedupDeletionResultCode.NOT_WRITABLE)
      assertThat(cbz).exists()
      verify(exactly = 0) { bookLifecycle.softDeleteMany(any()) }
    } finally {
      attributes.setReadOnly(original)
    }
  }

  @Test
  fun `missing file keys require matching hashes and still allow mtime drift`() {
    val cbz = Files.write(directory.resolve("no-key-stable.cbz"), "stable archive".toByteArray())
    val book = bookFor(cbz)
    val driftingHasher = mockk<Hasher>()
    every { driftingHasher.computeHash(cbz) } answers {
      Files.setLastModifiedTime(cbz, FileTime.fromMillis(System.currentTimeMillis() + 60_000))
      hasher.computeHash(cbz)
    }
    val withoutKeys = withoutFileKeys(driftingHasher)

    assertThat(withoutKeys.captureStrongIdentity(book).archiveHash).isEqualTo(hasher.computeHash(cbz))
    verify(exactly = 2) { driftingHasher.computeHash(cbz) }
    every { bookLifecycle.softDeleteMany(listOf(book)) } just Runs
    val expected = withoutKeys.captureStrongIdentity(book)
    assertThat(withoutKeys.deleteVerifiedBook(book, expected).code).isEqualTo(DedupDeletionResultCode.DELETED)
  }

  @Test
  fun `same-size replacement without file keys is rejected and never deleted`() {
    val cbz = Files.write(directory.resolve("no-key-replaced.cbz"), "first".toByteArray())
    val book = bookFor(cbz)
    val expected = lifecycle.captureStrongIdentity(book)
    val replacingHasher = mockk<Hasher>()
    every { replacingHasher.computeHash(cbz) } answers {
      val hash = hasher.computeHash(cbz)
      val replacement = Files.write(directory.resolve("no-key-replacement.cbz"), "other".toByteArray())
      Files.move(replacement, cbz, StandardCopyOption.REPLACE_EXISTING)
      hash
    }
    val withoutKeys = withoutFileKeys(replacingHasher)

    val result = withoutKeys.deleteVerifiedBook(book, expected)

    assertThat(result.code).isEqualTo(DedupDeletionResultCode.GENERATION_MISMATCH)
    assertThat(result.detail).contains("changed while its full archive hash")
    assertThat(cbz).exists()
    verify(exactly = 2) { replacingHasher.computeHash(cbz) }
    verify(exactly = 0) { bookLifecycle.softDeleteMany(any()) }
  }

  @Test
  fun `size changes during the fallback hash are rejected even when hashes match`() {
    val cbz = Files.write(directory.resolve("no-key-size-change.cbz"), "first".toByteArray())
    val book = bookFor(cbz)
    val changingHasher = mockk<Hasher>()
    var reads = 0
    every { changingHasher.computeHash(cbz) } answers {
      if (++reads == 2) Files.write(cbz, "changed during second read".toByteArray())
      "same-hash"
    }

    assertThatThrownBy { withoutFileKeys(changingHasher).captureStrongIdentity(book) }
      .hasMessageContaining("changed while its full archive hash")
  }

  private fun withoutFileKeys(testHasher: Hasher): DedupPhysicalBookDeletionLifecycle {
    val subject = spyk(DedupPhysicalBookDeletionLifecycle(testHasher, bookLifecycle))
    every { subject.readStableAttributes(any()) } answers {
      val attributes = callOriginal() as BasicFileAttributes
      object : BasicFileAttributes by attributes {
        override fun fileKey(): Any? = null
      }
    }
    return subject
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
