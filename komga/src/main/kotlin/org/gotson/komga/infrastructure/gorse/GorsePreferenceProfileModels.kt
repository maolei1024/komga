package org.gotson.komga.infrastructure.gorse

data class PreferenceAxis(
  val mean: Double = 30.0,
  val deviation: Double = 10.0,
)

data class PreferenceDistribution(
  val count: Int = 0,
  val pages: PreferenceAxis = PreferenceAxis(),
  val tags: PreferenceAxis = PreferenceAxis(20.0, 5.0),
)

data class GorsePreferenceProfile(
  val positive: PreferenceDistribution = PreferenceDistribution(),
  val negative: PreferenceDistribution = PreferenceDistribution(pages = PreferenceAxis(180.0, 20.0), tags = PreferenceAxis(40.0, 5.0)),
)

data class PreferenceCell(
  val pageBucket: String,
  val tagBucket: String,
  val positive: Int,
  val negative: Int,
  val available: Int,
)

data class PreferencePreview(
  val cells: List<PreferenceCell>,
  val conflicts: List<String>,
  val total: Int,
  val limit: Int = 5000,
)

data class PreferenceProfileStatus(
  val profile: GorsePreferenceProfile,
  val status: String,
  val batchId: String? = null,
  val delivered: Int = 0,
  val processed: Int = 0,
  val total: Int = 0,
  val lastError: String? = null,
  val previousProfile: GorsePreferenceProfile? = null,
)
