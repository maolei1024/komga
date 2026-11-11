export interface PreferenceAxis {
  mean: number
  deviation: number
}
export interface PreferenceDistribution {
  count: number
  pages: PreferenceAxis
  tags: PreferenceAxis
}
export interface PreferenceProfile {
  positive: PreferenceDistribution
  negative: PreferenceDistribution
}
export interface PreferenceCell {
  pageBucket: string
  tagBucket: string
  positive: number
  negative: number
  available: number
}
export interface PreferencePreview {
  cells: PreferenceCell[]
  conflicts: string[]
  total: number
  limit: number
}
export interface PreferenceProfileStatus {
  profile: PreferenceProfile
  status: string
  batchId: string | null
  delivered: number
  processed: number
  total: number
  lastError: string | null
  previousProfile: PreferenceProfile | null
}
export const emptyProfile = (): PreferenceProfile => ({
  positive: {count: 0, pages: {mean: 30, deviation: 10}, tags: {mean: 20, deviation: 5}},
  negative: {count: 0, pages: {mean: 180, deviation: 20}, tags: {mean: 40, deviation: 5}},
})
