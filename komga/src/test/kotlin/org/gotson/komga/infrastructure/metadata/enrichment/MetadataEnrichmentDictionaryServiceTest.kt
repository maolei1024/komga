package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MetadataEnrichmentDictionaryServiceTest {
  @TempDir
  lateinit var directory: Path

  @Test
  fun `bundled dictionary is seeded and overrides take precedence`() {
    val service = service()

    assertThat(service.baseEntryCount()).isGreaterThan(60_000)
    assertThat(service.lookup("tag", "big breasts")).isEqualTo("巨乳")

    service.putOverride(EhtagsDictionaryEntry(k = "big breasts", v = "自定义巨乳", t = "tag"))
    assertThat(service.lookup("tag", "BIG BREASTS")).isEqualTo("自定义巨乳")

    service.putOverride(EhtagsDictionaryEntry(k = "untranslated", v = "untranslated", t = "tag"))
    assertThat(service.lookup("tag", "untranslated")).isEqualTo("untranslated")
  }

  @Test
  fun `invalid replacement never changes the base dictionary`() {
    val service = service()
    val before = service.fingerprint()

    assertThatThrownBy { service.replaceBase("not-json".toByteArray()) }
      .isInstanceOf(Exception::class.java)

    assertThat(service.fingerprint()).isEqualTo(before)
    assertThat(service.lookup("tag", "big breasts")).isEqualTo("巨乳")
  }

  @Test
  fun `base dictionary accepts retained parody entries`() {
    val service = service()
    service.replaceBase(
      """[{"k":"series","v":"作品","t":"parody","n":1},{"k":"tag","v":"标签","t":"tag"}]""".toByteArray(),
    )

    assertThat(service.baseEntryCount()).isEqualTo(2)
    assertThat(service.lookup("parody", "series")).isEqualTo("作品")
  }

  private fun service(): MetadataEnrichmentDictionaryService {
    val properties = KomgaProperties().apply { configDir = directory.toString() }
    return MetadataEnrichmentDictionaryService(properties, jacksonObjectMapper()).also { it.initialize() }
  }
}
