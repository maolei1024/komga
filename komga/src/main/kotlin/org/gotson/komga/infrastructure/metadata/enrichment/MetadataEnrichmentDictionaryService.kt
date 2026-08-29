package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes

@Service
class MetadataEnrichmentDictionaryService(
  komgaProperties: KomgaProperties,
  private val objectMapper: ObjectMapper,
) {
  private val directory: Path = Path.of(komgaProperties.configDir ?: System.getProperty("user.home"), "metadata-enrichment")
  val basePath: Path = directory.resolve("ehtags-cn.json")
  val overridesPath: Path = directory.resolve("ehtags-overrides.json")

  @Volatile
  private var baseEntries: List<EhtagsDictionaryEntry> = emptyList()

  @Volatile
  private var overrideEntries: List<EhtagsDictionaryEntry> = emptyList()

  @Volatile
  private var translations: Map<String, String> = emptyMap()

  @PostConstruct
  fun initialize() {
    directory.createDirectories()
    if (!basePath.exists()) {
      ClassPathResource("metadata-enrichment/ehtags-cn.json").inputStream.use { input ->
        Files.copy(input, basePath)
      }
    }
    if (!overridesPath.exists()) writeAtomically(overridesPath, objectMapper.writeValueAsBytes(emptyList<EhtagsDictionaryEntry>()))
    reload()
  }

  @Synchronized
  fun reload() {
    baseEntries = readAndValidate(basePath.readBytes(), requireNonEmpty = true)
    overrideEntries = readAndValidate(overridesPath.readBytes(), requireNonEmpty = false)
    val merged = linkedMapOf<String, String>()
    baseEntries.forEach { entry -> merged[entry.key()] = entry.v.trim() }
    overrideEntries.forEach { entry -> merged[entry.key()] = entry.v.trim() }
    translations = merged
  }

  fun lookup(
    type: String,
    value: String,
  ): String? = translations[key(type, value)]

  fun baseEntryCount(): Int = baseEntries.size

  fun overrides(): List<EhtagsDictionaryEntry> = overrideEntries.sortedWith(compareBy({ it.t }, { it.k.lowercase() }))

  fun fingerprint(): String = sha256(basePath.readBytes() + overridesPath.readBytes())

  @Synchronized
  fun replaceBase(bytes: ByteArray) {
    readAndValidate(bytes, requireNonEmpty = true)
    writeAtomically(basePath, bytes)
    reload()
  }

  @Synchronized
  fun putOverride(entry: EhtagsDictionaryEntry) {
    validate(entry)
    val updated = overrideEntries.filterNot { it.key() == entry.key() } + entry.copy(k = entry.k.trim(), v = entry.v.trim(), t = normalizeType(entry.t))
    writeAtomically(overridesPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(updated.sortedWith(compareBy({ it.t }, { it.k.lowercase() }))))
    reload()
  }

  @Synchronized
  fun deleteOverride(
    type: String,
    key: String,
  ): Boolean {
    val updated = overrideEntries.filterNot { it.key() == key(type, key) }
    if (updated.size == overrideEntries.size) return false
    writeAtomically(overridesPath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(updated))
    reload()
    return true
  }

  private fun readAndValidate(
    bytes: ByteArray,
    requireNonEmpty: Boolean,
  ): List<EhtagsDictionaryEntry> {
    val entries = objectMapper.readValue(bytes, object : TypeReference<List<EhtagsDictionaryEntry>>() {})
    if (requireNonEmpty) require(entries.isNotEmpty()) { "Dictionary cannot be empty" }
    entries.forEach(::validate)
    return entries
  }

  private fun validate(entry: EhtagsDictionaryEntry) {
    require(entry.k.isNotBlank()) { "Dictionary key cannot be blank" }
    require(entry.v.isNotBlank()) { "Dictionary value cannot be blank" }
    normalizeType(entry.t)
  }

  private fun EhtagsDictionaryEntry.key() = key(t, k)

  private fun key(
    type: String,
    value: String,
  ) = "${normalizeType(type)}:${value.trim().lowercase()}"

  private fun normalizeType(type: String): String {
    val normalized = type.trim().lowercase().ifBlank { "tag" }
    require(normalized in SUPPORTED_TYPES) { "Unsupported dictionary type: $type" }
    return normalized
  }

  private fun writeAtomically(
    target: Path,
    bytes: ByteArray,
  ) {
    val temporary = Files.createTempFile(directory, ".${target.fileName}.", ".tmp")
    try {
      Files.write(temporary, bytes)
      try {
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: Exception) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
      }
    } finally {
      Files.deleteIfExists(temporary)
    }
  }

  private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

  companion object {
    // The bundled e-hentai dictionary also contains parody entries. They are
    // retained for file compatibility even though enrichment only indexes the
    // four self-nhentai source tag types below.
    private val SUPPORTED_TYPES = setOf("tag", "character", "artist", "group", "parody")
  }
}
