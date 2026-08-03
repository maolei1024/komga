import {
  DedupCaseVerificationRequestDto,
  DedupEligibilityReasonDto,
  DedupReviewCaseDto,
} from '@/types/komga-dedup'

export type DedupEligibilityEffect = 'BLOCK_SUGGESTED' | 'BLOCK_MANUAL' | 'CONFIRM_SUGGESTED' | 'CONFIRM_MANUAL' | 'WARNING'

export interface DedupEligibilityDisplayReason {
  key: string
  code: string
  severity: 'BLOCKER' | 'WARNING'
  memberIds: string[]
  actual?: unknown
  threshold?: unknown
  pageRanges: string[]
  actions: string[]
  effects: DedupEligibilityEffect[]
}

export function hasPageEvidence(reviewCase: DedupReviewCaseDto): boolean {
  return reviewCase.coverageLeft != null && reviewCase.coverageRight != null
}

export function unmatchedPageCount(reviewCase: DedupReviewCaseDto): number | null {
  const counts = [reviewCase.unmatchedPrefixCount, reviewCase.unmatchedSuffixCount, reviewCase.unmatchedInternalCount]
  const present = counts.filter((value): value is number => value != null)
  return present.length === counts.length ? present.reduce((sum, value) => sum + value, 0) : null
}

export function currentPageVerificationRequests(cases: DedupReviewCaseDto[]): DedupCaseVerificationRequestDto[] {
  return cases
    .filter(reviewCase => reviewCase.origin !== 'EXACT_FILE')
    .map(reviewCase => ({caseId: reviewCase.id, expectedRevision: reviewCase.revision}))
}

export function mergeEligibilityReasons(reviewCase: DedupReviewCaseDto): DedupEligibilityDisplayReason[] {
  const grouped = new Map<string, DedupEligibilityDisplayReason>()
  const reasons = [...reviewCase.eligibility.blockers, ...reviewCase.eligibility.warnings]
  reasons.forEach(reason => {
    const memberIds = [...reason.memberIds].sort()
    const key = `${reason.code}:${memberIds.join(',')}`
    const existing = grouped.get(key) || {
      key,
      code: reason.code,
      severity: 'WARNING',
      memberIds,
      pageRanges: [],
      actions: [],
      effects: [],
    } as DedupEligibilityDisplayReason
    if (reason.severity === 'BLOCKER') existing.severity = 'BLOCKER'
    if (existing.actual === undefined && reason.actual != null) existing.actual = reason.actual
    if (existing.threshold === undefined && reason.threshold != null) existing.threshold = reason.threshold
    existing.pageRanges = unique([...existing.pageRanges, ...reason.pageRanges])
    existing.actions = unique([...existing.actions, ...(reason.action ? [reason.action] : [])])
    existing.effects = unique([...existing.effects, ...reasonEffects(reason)])
    grouped.set(key, existing)
  })
  return [...grouped.values()]
}

function reasonEffects(reason: DedupEligibilityReasonDto): DedupEligibilityEffect[] {
  if (reason.severity === 'BLOCKER') {
    return reason.appliesTo.map(action => action === 'SUGGESTED' ? 'BLOCK_SUGGESTED' : 'BLOCK_MANUAL')
  }
  if (reason.confirmationRequired) {
    return reason.appliesTo.map(action => action === 'SUGGESTED' ? 'CONFIRM_SUGGESTED' : 'CONFIRM_MANUAL')
  }
  return ['WARNING']
}

function unique<T>(values: T[]): T[] {
  return [...new Set(values)]
}
