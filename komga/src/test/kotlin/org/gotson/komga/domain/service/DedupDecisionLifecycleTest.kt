package org.gotson.komga.domain.service

import com.ninjasquad.springmockk.MockkBean
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.assertj.core.api.Assertions.assertThat
import org.gotson.komga.application.tasks.Task
import org.gotson.komga.application.tasks.TaskProcessor
import org.gotson.komga.application.tasks.TasksRepository
import org.gotson.komga.domain.model.DedupDecisionItemState
import org.gotson.komga.domain.model.DedupDecisionState
import org.gotson.komga.domain.model.DedupLibrarySettings
import org.gotson.komga.domain.model.ReadList
import org.gotson.komga.domain.model.makeBook
import org.gotson.komga.domain.model.makeLibrary
import org.gotson.komga.domain.model.makeSeries
import org.gotson.komga.domain.persistence.BookRepository
import org.gotson.komga.domain.persistence.DedupDecisionRepository
import org.gotson.komga.domain.persistence.DedupRepository
import org.gotson.komga.domain.persistence.LibraryRepository
import org.gotson.komga.domain.persistence.ReadListRepository
import org.gotson.komga.domain.persistence.SeriesRepository
import org.gotson.komga.infrastructure.hash.Hasher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDateTime
import java.time.ZoneId

@SpringBootTest
class DedupDecisionLifecycleTest(
  @Autowired private val decisionLifecycle: DedupDecisionLifecycle,
  @Autowired private val eligibilityPolicy: DedupEligibilityPolicy,
  @Autowired private val exactLifecycle: DedupExactDuplicateLifecycle,
  @Autowired private val dedupRepository: DedupRepository,
  @Autowired private val decisionRepository: DedupDecisionRepository,
  @Autowired private val libraryRepository: LibraryRepository,
  @Autowired private val bookRepository: BookRepository,
  @Autowired private val seriesRepository: SeriesRepository,
  @Autowired private val readListRepository: ReadListRepository,
  @Autowired private val seriesLifecycle: SeriesLifecycle,
  @Autowired private val tasksRepository: TasksRepository,
  @Autowired private val taskProcessor: TaskProcessor,
) {
  @TempDir
  lateinit var directory: Path

  @MockkBean
  private lateinit var eventPublisher: ApplicationEventPublisher

  private val hasher = Hasher()
  private val library = makeLibrary("dedup-decision")

  @BeforeEach
  fun setup() {
    taskProcessor.processTasks = false
    every { eventPublisher.publishEvent(any()) } just Runs
    libraryRepository.insert(library)
    dedupRepository.saveLibrarySettings(
      DedupLibrarySettings(
        libraryId = library.id,
        enabled = true,
        completionStabilitySeconds = 0,
      ),
    )
  }

  @AfterEach
  fun cleanup() {
    tasksRepository.deleteAll()
    dedupRepository.deleteAllDedupData()
    readListRepository.deleteAll()
    seriesLifecycle.deleteMany(seriesRepository.findAll())
    libraryRepository.deleteAll()
    taskProcessor.processTasks = true
  }

  @Test
  fun `approved exact-file decision deletes one CBZ only and completes after stable DB reconciliation`() {
    val fixture = createExactPair()
    val companionPdf = Files.write(directory.resolve("loser.pdf"), "companion".toByteArray())
    val sidecar = Files.write(directory.resolve("loser.jpg"), "sidecar".toByteArray())
    val decision = approveSuggested(fixture.caseId, fixture.keeperId)

    decisionLifecycle.requestExecution(decision.id)
    decisionLifecycle.applyDecision(decision.id)

    assertThat(fixture.keeperPath).exists()
    assertThat(fixture.loserPath).doesNotExist()
    assertThat(companionPdf).exists()
    assertThat(sidecar).exists()
    assertThat(bookRepository.findByIdOrNull(fixture.loserId)?.deletedDate).isNotNull
    val submitted = decisionRepository.findDecisionItems(decision.id).single()
    assertThat(submitted.state).isEqualTo(DedupDecisionItemState.DB_SOFT_DELETED)

    decisionLifecycle.verifyDeletion(submitted.id)

    assertThat(decisionRepository.findDecisionItems(decision.id).single().state).isEqualTo(DedupDecisionItemState.CONFIRMED)
    assertThat(decisionRepository.findDecision(decision.id)?.state).isEqualTo(DedupDecisionState.COMPLETED)
    assertThat(tasksRepository.findAll()).allMatch { it is Task.DrainDedupQueue }
  }

  @Test
  fun `local state added after approval stops execution and requires a fresh approval`() {
    val fixture = createExactPair()
    val decision = approveSuggested(fixture.caseId, fixture.keeperId)
    readListRepository.insert(ReadList(name = "state added after approval", bookIds = sortedMapOf(0 to fixture.loserId)))

    decisionLifecycle.requestExecution(decision.id)
    decisionLifecycle.applyDecision(decision.id)

    assertThat(fixture.loserPath).exists()
    assertThat(bookRepository.findByIdOrNull(fixture.loserId)?.deletedDate).isNull()
    assertThat(decisionRepository.findDecision(decision.id)?.state).isEqualTo(DedupDecisionState.REAPPROVAL_REQUIRED)
  }

  @Test
  fun `one successful unlink followed by a non-writable loser remains an honest partial saga`() {
    val fixture = createExactGroup(3)
    val decision = approveSuggested(fixture.caseId, fixture.keeperId)
    val orderedItems = decisionRepository.findDecisionItems(decision.id)
    val secondPath = fixture.paths.getValue(orderedItems[1].bookId)
    val originalPermissions = Files.getPosixFilePermissions(secondPath)
    Files.setPosixFilePermissions(secondPath, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ))
    try {
      decisionLifecycle.requestExecution(decision.id)
      decisionLifecycle.applyDecision(decision.id)

      val results = decisionRepository.findDecisionItems(decision.id)
      assertThat(results.map { it.state }).containsExactly(DedupDecisionItemState.DB_SOFT_DELETED, DedupDecisionItemState.FAILED)
      assertThat(fixture.paths.getValue(results[0].bookId)).doesNotExist()
      assertThat(secondPath).exists()
      assertThat(decisionRepository.findDecision(decision.id)?.state).isEqualTo(DedupDecisionState.PARTIALLY_COMPLETED)
    } finally {
      if (Files.exists(secondPath)) Files.setPosixFilePermissions(secondPath, originalPermissions)
    }
  }

  private fun approveSuggested(
    caseId: String,
    keeperId: String,
  ): org.gotson.komga.domain.model.DedupDecision {
    val initial = requireNotNull(dedupRepository.findReviewCase(caseId))
    assertThat(dedupRepository.setReviewCaseKeeper(caseId, initial.revision, keeperId)).isTrue
    val current = requireNotNull(dedupRepository.findReviewCase(caseId))
    val eligibility = eligibilityPolicy.evaluate(current)
    assertThat(eligibility.suggestedPlanEligible).isTrue
    return decisionLifecycle.createSuggested(caseId, current.revision, eligibility.stateRevision, "admin")
  }

  private fun createExactPair(): ExactPairFixture {
    val group = createExactGroup(2)
    val loserId = group.paths.keys.single { it != group.keeperId }
    return ExactPairFixture(group.caseId, group.keeperId, loserId, group.paths.getValue(group.keeperId), group.paths.getValue(loserId))
  }

  private fun createExactGroup(count: Int): ExactGroupFixture {
    val bytes = "same exact archive".toByteArray()
    val paths = (0 until count).map { index -> Files.write(directory.resolve(if (index == 0) "keeper.cbz" else "loser-$index.cbz"), bytes) }
    val series =
      paths.indices.map { index -> seriesLifecycle.createSeries(makeSeries(if (index == 0) "Keeper series" else "Loser series $index", library.id)) }
    paths.indices.forEach { index ->
      seriesLifecycle.addBooks(series[index], listOf(persistedBook(paths[index], series[index].id, if (index == 0) "Keeper" else "Loser $index")))
    }
    val persisted = series.map { bookRepository.findAllBySeriesId(it.id).single() }
    assertThat(exactLifecycle.reconcileLibrary(library.id)).isEqualTo(1)
    val reviewCase = dedupRepository.findReviewCases(library.id).single()
    return ExactGroupFixture(reviewCase.id, persisted.first().id, persisted.zip(paths).associate { (book, path) -> book.id to path })
  }

  private fun persistedBook(
    path: Path,
    seriesId: String,
    name: String,
  ) = makeBook(
    name,
    libraryId = library.id,
    seriesId = seriesId,
    url = path.toUri().toURL(),
  ).copy(
    fileHash = hasher.computeHash(path),
    fileSize = Files.size(path),
    fileLastModified = LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault()),
    oneshot = true,
  )

  private data class ExactPairFixture(
    val caseId: String,
    val keeperId: String,
    val loserId: String,
    val keeperPath: Path,
    val loserPath: Path,
  )

  private data class ExactGroupFixture(
    val caseId: String,
    val keeperId: String,
    val paths: Map<String, Path>,
  )
}
