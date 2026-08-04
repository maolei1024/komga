import {currentPageVerificationRequests, formatBytes, mergeEligibilityReasons, resolutionSeriesResults} from '@/functions/dedup'
import {DedupClusterSummaryDto, DedupEligibilityReasonDto} from '@/types/komga-dedup'

describe('duplicate cluster helpers', () => {
  it('freezes every reviewable current-page cluster id and revision', () => {
    const clusters = [summary('cover', 3, true), summary('verified', 7, true), summary('dormant', 2, false)]

    expect(currentPageVerificationRequests(clusters)).toEqual([
      {clusterId: 'cover', expectedRevision: 3},
      {clusterId: 'verified', expectedRevision: 7},
    ])
  })

  it('merges repeated reasons and preserves a real zero while omitting null detail', () => {
    const merged = mergeEligibilityReasons([
      reason('LOW_COVERAGE', 'WARNING', ['B', 'A'], 0, null),
      reason('LOW_COVERAGE', 'BLOCKER', ['A', 'B'], null, 0.9),
    ])

    expect(merged).toHaveLength(1)
    expect(merged[0].severity).toBe('BLOCKER')
    expect(merged[0].memberIds).toEqual(['A', 'B'])
    expect(merged[0].actual).toBe(0)
    expect(merged[0].threshold).toBe(0.9)
  })

  it('formats zero and unavailable file sizes explicitly', () => {
    expect(formatBytes(0)).toBe('0 B')
    expect(formatBytes(null)).toBe('—')
    expect(formatBytes(1024 * 1024)).toBe('1.0 MB')
  })

  it('exposes every persisted per-Series Gorse result in stable order', () => {
    expect(resolutionSeriesResults({series: {
      z: {seriesId: 'z', state: 'FAILED', expectedHidden: true, error: 'readback mismatch'},
      a: {seriesId: 'a', state: 'NOT_APPLICABLE', expectedHidden: null, error: null},
    }})).toEqual([
      {seriesId: 'a', state: 'NOT_APPLICABLE', expectedHidden: null, error: null},
      {seriesId: 'z', state: 'FAILED', expectedHidden: true, error: 'readback mismatch'},
    ])
  })
})

function summary(id: string, revision: number, reviewable: boolean): DedupClusterSummaryDto {
  return {
    id, revision, reviewable, libraryId: 'library', status: 'UNPROCESSED', memberCount: 2, coverMembers: [],
    verifiedPairs: 0, totalPairs: 1, evidenceMaturity: 'COVER_ONLY', suggestionPlanAvailable: false,
    suggestedPlanEligible: false, suggestedKeepCount: 2, suggestedDeleteCount: 0, lastModified: '2026-08-04T00:00:00Z',
  }
}

function reason(code: string, severity: 'BLOCKER' | 'WARNING', memberIds: string[], actual: unknown, threshold: unknown): DedupEligibilityReasonDto {
  return {code, severity, memberIds, actual, threshold, appliesTo: ['SUGGESTED'], confirmationRequired: false, scope: 'PAIR'}
}
