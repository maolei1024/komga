import {
  DedupClusterSummaryDto,
  DedupClusterVerificationRequestDto,
  DedupEligibilityReasonDto,
  DedupGorseSeriesResultDto,
  DedupResolutionResultPayloadDto,
} from '@/types/komga-dedup'

export interface DedupEligibilityDisplayReason {
  key: string
  code: string
  severity: 'BLOCKER' | 'WARNING'
  memberIds: string[]
  actual?: unknown
  threshold?: unknown
  actions: string[]
  confirmationRequired: boolean
}

export function currentPageVerificationRequests(clusters: DedupClusterSummaryDto[]): DedupClusterVerificationRequestDto[] {
  return clusters.filter(cluster => cluster.reviewable).map(cluster => ({clusterId: cluster.id, expectedRevision: cluster.revision}))
}

export function mergeEligibilityReasons(reasons: DedupEligibilityReasonDto[]): DedupEligibilityDisplayReason[] {
  const grouped = new Map<string, DedupEligibilityDisplayReason>()
  reasons.forEach(reason => {
    const memberIds = [...reason.memberIds].sort()
    const key = `${reason.code}:${memberIds.join(',')}`
    const current = grouped.get(key) || {
      key,
      code: reason.code,
      severity: 'WARNING',
      memberIds,
      actions: [],
      confirmationRequired: false,
    } as DedupEligibilityDisplayReason
    if (reason.severity === 'BLOCKER') current.severity = 'BLOCKER'
    if (current.actual === undefined && reason.actual != null) current.actual = reason.actual
    if (current.threshold === undefined && reason.threshold != null) current.threshold = reason.threshold
    current.actions = [...new Set([...current.actions, ...(reason.action ? [reason.action] : [])])]
    current.confirmationRequired = current.confirmationRequired || reason.confirmationRequired
    grouped.set(key, current)
  })
  return [...grouped.values()]
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  if (bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / Math.pow(1024, index)).toFixed(index > 1 ? 1 : 0)} ${units[index]}`
}

export function resolutionSeriesResults(result: DedupResolutionResultPayloadDto | null | undefined): DedupGorseSeriesResultDto[] {
  if (!result?.series || typeof result.series !== 'object') return []
  return Object.entries(result.series).map(([seriesId, value]) => ({
    seriesId,
    state: value.state,
    expectedHidden: value.expectedHidden,
    error: value.error,
  })).sort((left, right) => left.seriesId.localeCompare(right.seriesId))
}
