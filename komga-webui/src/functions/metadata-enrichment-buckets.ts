import {MetadataEnrichmentBucket} from '@/services/komga-metadata-enrichment.service'

export const BUCKET_INT_MAX = 2147483647

export function bucketLabel(bucket: MetadataEnrichmentBucket, prefix: string): string {
  return `${prefix}${bucket.max === null ? `${bucket.min - 1}+` : `${bucket.min}-${bucket.max}`}`
}

export function bucketErrors(buckets: MetadataEnrichmentBucket[], start: number, prefix: string): string[] {
  return buckets.map((bucket, index) => {
    if (!Number.isInteger(bucket.min) || bucket.min < start || bucket.min > BUCKET_INT_MAX)
      return '起始值必须是有效整数'
    if (index === 0 && bucket.min !== start) return `首档必须从 ${start} 开始`
    if (index > 0 && (buckets[index - 1].max === null || bucket.min !== Number(buckets[index - 1].max) + 1))
      return '区间必须连续且不重叠'
    if (index === buckets.length - 1 && bucket.max !== null) return '最后一档必须无上限'
    if (index < buckets.length - 1 && (bucket.max === null || !Number.isInteger(bucket.max) || bucket.max < bucket.min || bucket.max >= BUCKET_INT_MAX))
      return `结束值必须是 ${bucket.min} 至 ${BUCKET_INT_MAX - 1} 的整数`
    const label = bucket.label.trim()
    if (!label.startsWith(prefix)) return `标签必须以 ${prefix} 开头`
    if (buckets.some((other, otherIndex) => otherIndex !== index && other.label.trim() === label)) return '标签不能重复'
    return ''
  })
}

export function validBuckets(buckets: MetadataEnrichmentBucket[], start: number, prefix: string): boolean {
  return buckets.length > 0 && bucketErrors(buckets, start, prefix).every(error => !error)
}
