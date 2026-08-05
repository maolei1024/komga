package org.gotson.komga.infrastructure.dedup

import org.gotson.komga.domain.model.DedupFeature
import org.gotson.komga.domain.model.DedupFeatureState
import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicReference

data class CoverNeighbor(
  val bookLowId: String,
  val bookHighId: String,
  val distance: Int,
)

@Service
class CoverSimilarityIndex(
  private val hasher: CoverPerceptualHasher,
) {
  private val snapshots = AtomicReference<Map<String, Snapshot>>(emptyMap())

  fun replaceLibrary(
    libraryId: String,
    features: Collection<DedupFeature>,
    threshold: Int,
  ) {
    val entries =
      features
        .filter { it.coverHash?.size == 32 }
        .associate { it.bookId to IndexedCover(it.bookId, it.sourceCoverGeneration, it.coverHash!!) }
    val replacement = Snapshot(entries, threshold)
    snapshots.updateAndGet { current -> current + (libraryId to replacement) }
  }

  fun removeLibrary(libraryId: String) {
    snapshots.updateAndGet { it - libraryId }
  }

  fun count(libraryId: String): Int = snapshots.get()[libraryId]?.entries?.size ?: 0

  fun upsertFeature(
    libraryId: String,
    feature: DedupFeature,
    threshold: Int,
  ) {
    snapshots.updateAndGet { current ->
      val previous = current[libraryId]
      val entries = previous?.entries.orEmpty().toMutableMap()
      if (feature.coverState == DedupFeatureState.READY && feature.coverHash?.size == 32) {
        entries[feature.bookId] = IndexedCover(feature.bookId, feature.sourceCoverGeneration, feature.coverHash)
      } else {
        entries.remove(feature.bookId)
      }
      current + (libraryId to Snapshot(entries, threshold))
    }
  }

  fun removeFeature(
    libraryId: String,
    bookId: String,
    threshold: Int,
  ) {
    snapshots.updateAndGet { current ->
      val entries = current[libraryId]?.entries.orEmpty() - bookId
      current + (libraryId to Snapshot(entries, threshold))
    }
  }

  fun findNeighbors(
    libraryId: String,
    bookId: String,
    topK: Int,
  ): List<CoverNeighbor> {
    val snapshot = snapshots.get()[libraryId] ?: return emptyList()
    val entry = snapshot.entries[bookId] ?: return emptyList()
    return snapshot
      .candidateIds(entry)
      .asSequence()
      .filter { it != bookId }
      .mapNotNull { candidateId ->
        val candidate = snapshot.entries[candidateId] ?: return@mapNotNull null
        val distance = hasher.distance(entry.hash, candidate.hash)
        candidate.takeIf { distance <= snapshot.threshold }?.let {
          val (low, high) = if (bookId < candidateId) bookId to candidateId else candidateId to bookId
          CoverNeighbor(low, high, distance)
        }
      }.sortedWith(compareBy<CoverNeighbor> { it.distance }.thenBy { it.bookLowId }.thenBy { it.bookHighId })
      .take(topK)
      .toList()
  }

  private data class IndexedCover(
    val bookId: String,
    val generation: String,
    val hash: ByteArray,
  )

  private data class BandKey(
    val band: Int,
    val fingerprint: Long,
  )

  private class Snapshot(
    val entries: Map<String, IndexedCover>,
    val threshold: Int,
  ) {
    private val bandCount = (threshold + 1).coerceIn(1, 256)
    private val buckets: Map<BandKey, Set<String>> =
      buildMap<BandKey, MutableSet<String>> {
        this@Snapshot.entries.values.forEach { entry ->
          bandKeys(entry.hash).forEach { key -> getOrPut(key) { mutableSetOf() }.add(entry.bookId) }
        }
      }.mapValues { it.value.toSet() }

    fun candidateIds(entry: IndexedCover): Set<String> = if (threshold >= 256) entries.keys else bandKeys(entry.hash).flatMapTo(mutableSetOf()) { buckets[it].orEmpty() }

    private fun bandKeys(hash: ByteArray): List<BandKey> =
      (0 until bandCount).map { band ->
        val start = band * 256 / bandCount
        val end = (band + 1) * 256 / bandCount
        var fingerprint = -0x340d631b7bdddcdbL
        for (bit in start until end) {
          val value = (hash[bit / 8].toInt() ushr (7 - bit % 8)) and 1
          fingerprint = (fingerprint xor value.toLong()) * 0x100000001b3L
        }
        BandKey(band, fingerprint)
      }
  }
}
