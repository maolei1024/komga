package org.gotson.komga.infrastructure.metadata.enrichment

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.gotson.komga.domain.model.MetadataEnrichmentBucket
import org.gotson.komga.domain.model.MetadataEnrichmentDictionaryUpdatePolicy
import org.gotson.komga.infrastructure.jooq.main.ServerSettingsDao
import org.springframework.stereotype.Service

@Service
class MetadataEnrichmentSettingsProvider(
  private val serverSettingsDao: ServerSettingsDao,
  private val objectMapper: ObjectMapper,
) {
  var aiEnabled: Boolean = boolean(Setting.AI_ENABLED, false)
    set(value) {
      serverSettingsDao.saveSetting(Setting.AI_ENABLED.name, value)
      field = value
    }

  var aiAutoOnNew: Boolean = boolean(Setting.AI_AUTO_ON_NEW, true)
    set(value) {
      serverSettingsDao.saveSetting(Setting.AI_AUTO_ON_NEW.name, value)
      field = value
    }

  var aiBaseUrl: String = string(Setting.AI_BASE_URL, "")
    set(value) {
      serverSettingsDao.saveSetting(Setting.AI_BASE_URL.name, value.trim())
      field = value.trim()
    }

  var aiModel: String = string(Setting.AI_MODEL, "")
    set(value) {
      serverSettingsDao.saveSetting(Setting.AI_MODEL.name, value.trim())
      field = value.trim()
    }

  var aiApiKey: String = string(Setting.AI_API_KEY, "")
    set(value) {
      if (value.isBlank()) serverSettingsDao.deleteSetting(Setting.AI_API_KEY.name) else serverSettingsDao.saveSetting(Setting.AI_API_KEY.name, value.trim())
      field = value.trim()
    }

  var aiTimeoutSeconds: Int = int(Setting.AI_TIMEOUT_SECONDS, 60)
    set(value) {
      require(value in 1..600) { "AI timeout must be between 1 and 600 seconds" }
      serverSettingsDao.saveSetting(Setting.AI_TIMEOUT_SECONDS.name, value)
      field = value
    }

  var aiMaxRetries: Int = int(Setting.AI_MAX_RETRIES, 3)
    set(value) {
      require(value in 0..10) { "AI max retries must be between 0 and 10" }
      serverSettingsDao.saveSetting(Setting.AI_MAX_RETRIES.name, value)
      field = value
    }

  var dictionaryUpdatePolicy: MetadataEnrichmentDictionaryUpdatePolicy =
    string(Setting.DICTIONARY_UPDATE_POLICY, MetadataEnrichmentDictionaryUpdatePolicy.MARK_STALE.name)
      .let { runCatching { MetadataEnrichmentDictionaryUpdatePolicy.valueOf(it) }.getOrDefault(MetadataEnrichmentDictionaryUpdatePolicy.MARK_STALE) }
    set(value) {
      serverSettingsDao.saveSetting(Setting.DICTIONARY_UPDATE_POLICY.name, value.name)
      field = value
    }

  var pageSizeBuckets: List<MetadataEnrichmentBucket> = buckets(Setting.PAGE_SIZE_BUCKETS, DEFAULT_PAGE_SIZE_BUCKETS)
    set(value) {
      validateBuckets(value, 1, "pageSize_")
      serverSettingsDao.saveSetting(Setting.PAGE_SIZE_BUCKETS.name, objectMapper.writeValueAsString(value))
      field = value
    }

  var tagSizeBuckets: List<MetadataEnrichmentBucket> = buckets(Setting.TAG_SIZE_BUCKETS, DEFAULT_TAG_SIZE_BUCKETS)
    set(value) {
      validateBuckets(value, 0, "tagSize_")
      serverSettingsDao.saveSetting(Setting.TAG_SIZE_BUCKETS.name, objectMapper.writeValueAsString(value))
      field = value
    }

  var lastDictionaryHash: String = string(Setting.LAST_DICTIONARY_HASH, "")
    set(value) {
      serverSettingsDao.saveSetting(Setting.LAST_DICTIONARY_HASH.name, value)
      field = value
    }

  var bootstrapCompleted: Boolean = boolean(Setting.BOOTSTRAP_COMPLETED, false)
    set(value) {
      serverSettingsDao.saveSetting(Setting.BOOTSTRAP_COMPLETED.name, value)
      field = value
    }

  val aiConfigured: Boolean
    get() = aiBaseUrl.isNotBlank() && aiModel.isNotBlank() && aiApiKey.isNotBlank()

  fun clearAiApiKey() {
    aiApiKey = ""
  }

  private fun boolean(
    setting: Setting,
    default: Boolean,
  ) = serverSettingsDao.getSettingByKey(setting.name, Boolean::class.java) ?: default

  private fun int(
    setting: Setting,
    default: Int,
  ) = serverSettingsDao.getSettingByKey(setting.name, Int::class.java) ?: default

  private fun string(
    setting: Setting,
    default: String,
  ) = serverSettingsDao.getSettingByKey(setting.name, String::class.java) ?: default

  private fun buckets(
    setting: Setting,
    default: List<MetadataEnrichmentBucket>,
  ): List<MetadataEnrichmentBucket> =
    serverSettingsDao
      .getSettingByKey(setting.name, String::class.java)
      ?.let {
        runCatching { objectMapper.readValue(it, object : TypeReference<List<MetadataEnrichmentBucket>>() {}) }.getOrNull()
      }?.takeIf { it.isNotEmpty() } ?: default

  companion object {
    val DEFAULT_PAGE_SIZE_BUCKETS =
      listOf(
        MetadataEnrichmentBucket(1, 10, "pageSize_1-10"),
        MetadataEnrichmentBucket(11, 30, "pageSize_11-30"),
        MetadataEnrichmentBucket(31, 70, "pageSize_31-70"),
        MetadataEnrichmentBucket(71, 150, "pageSize_71-150"),
        MetadataEnrichmentBucket(151, null, "pageSize_150+"),
      )

    val DEFAULT_TAG_SIZE_BUCKETS =
      listOf(
        MetadataEnrichmentBucket(0, 5, "tagSize_0-5"),
        MetadataEnrichmentBucket(6, 10, "tagSize_6-10"),
        MetadataEnrichmentBucket(11, 15, "tagSize_11-15"),
        MetadataEnrichmentBucket(16, 20, "tagSize_16-20"),
        MetadataEnrichmentBucket(21, 25, "tagSize_21-25"),
        MetadataEnrichmentBucket(26, 30, "tagSize_26-30"),
        MetadataEnrichmentBucket(31, 35, "tagSize_31-35"),
        MetadataEnrichmentBucket(36, 40, "tagSize_36-40"),
        MetadataEnrichmentBucket(41, 45, "tagSize_41-45"),
        MetadataEnrichmentBucket(46, 50, "tagSize_46-50"),
        MetadataEnrichmentBucket(51, null, "tagSize_50+"),
      )

    fun validateBuckets(
      buckets: List<MetadataEnrichmentBucket>,
      expectedStart: Int,
      labelPrefix: String,
    ) {
      require(buckets.isNotEmpty()) { "Buckets cannot be empty" }
      require(buckets.first().min == expectedStart) { "Buckets must start at $expectedStart" }
      require(buckets.last().max == null) { "The last bucket must be open ended" }
      require(buckets.map { it.label }.toSet().size == buckets.size) { "Bucket labels must be unique" }
      buckets.forEachIndexed { index, bucket ->
        require(bucket.min >= 0) { "Bucket minimum cannot be negative" }
        require(bucket.label.isNotBlank() && bucket.label.startsWith(labelPrefix)) { "Bucket label must start with $labelPrefix" }
        bucket.max?.let { require(it >= bucket.min) { "Bucket maximum cannot be smaller than minimum" } }
        if (index < buckets.lastIndex) {
          val maximum = requireNotNull(bucket.max) { "Only the last bucket can be open ended" }
          require(buckets[index + 1].min == maximum + 1) { "Buckets must be contiguous and non-overlapping" }
        }
      }
    }
  }

  private enum class Setting {
    METADATA_ENRICHMENT_AI_ENABLED,
    METADATA_ENRICHMENT_AI_AUTO_ON_NEW,
    METADATA_ENRICHMENT_AI_BASE_URL,
    METADATA_ENRICHMENT_AI_MODEL,
    METADATA_ENRICHMENT_AI_API_KEY,
    METADATA_ENRICHMENT_AI_TIMEOUT_SECONDS,
    METADATA_ENRICHMENT_AI_MAX_RETRIES,
    METADATA_ENRICHMENT_DICTIONARY_UPDATE_POLICY,
    METADATA_ENRICHMENT_PAGE_SIZE_BUCKETS,
    METADATA_ENRICHMENT_TAG_SIZE_BUCKETS,
    METADATA_ENRICHMENT_LAST_DICTIONARY_HASH,
    METADATA_ENRICHMENT_BOOTSTRAP_COMPLETED,
    ;

    companion object {
      val AI_ENABLED = METADATA_ENRICHMENT_AI_ENABLED
      val AI_AUTO_ON_NEW = METADATA_ENRICHMENT_AI_AUTO_ON_NEW
      val AI_BASE_URL = METADATA_ENRICHMENT_AI_BASE_URL
      val AI_MODEL = METADATA_ENRICHMENT_AI_MODEL
      val AI_API_KEY = METADATA_ENRICHMENT_AI_API_KEY
      val AI_TIMEOUT_SECONDS = METADATA_ENRICHMENT_AI_TIMEOUT_SECONDS
      val AI_MAX_RETRIES = METADATA_ENRICHMENT_AI_MAX_RETRIES
      val DICTIONARY_UPDATE_POLICY = METADATA_ENRICHMENT_DICTIONARY_UPDATE_POLICY
      val PAGE_SIZE_BUCKETS = METADATA_ENRICHMENT_PAGE_SIZE_BUCKETS
      val TAG_SIZE_BUCKETS = METADATA_ENRICHMENT_TAG_SIZE_BUCKETS
      val LAST_DICTIONARY_HASH = METADATA_ENRICHMENT_LAST_DICTIONARY_HASH
      val BOOTSTRAP_COMPLETED = METADATA_ENRICHMENT_BOOTSTRAP_COMPLETED
    }
  }
}
