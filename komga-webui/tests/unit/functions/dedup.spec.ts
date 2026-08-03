import {
  currentPageVerificationRequests,
  hasPageEvidence,
  mergeEligibilityReasons,
  unmatchedPageCount,
} from '@/functions/dedup'
import {DedupEligibilityReasonDto, DedupReviewCaseDto} from '@/types/komga-dedup'

describe('dedup presentation helpers', () => {
  test('distinguishes missing evidence from valid zero values', () => {
    const missing = reviewCase()
    const zero = reviewCase({
      coverageLeft: 0,
      coverageRight: 0,
      unmatchedPrefixCount: 0,
      unmatchedSuffixCount: 0,
      unmatchedInternalCount: 0,
    })

    expect(hasPageEvidence(missing)).toBe(false)
    expect(unmatchedPageCount(missing)).toBeNull()
    expect(hasPageEvidence(zero)).toBe(true)
    expect(unmatchedPageCount(zero)).toBe(0)
  })

  test('merges action-specific reasons without rendering null metrics', () => {
    const blocker = reason('COVER_ONLY', 'BLOCKER', ['SUGGESTED'], false)
    const warning = reason('COVER_ONLY', 'WARNING', ['MANUAL'], true)
    const merged = mergeEligibilityReasons(reviewCase({
      eligibility: {
        ...reviewCase().eligibility,
        blockers: [blocker],
        warnings: [warning],
      },
    }))

    expect(merged).toHaveLength(1)
    expect(merged[0].effects).toEqual(['BLOCK_SUGGESTED', 'CONFIRM_MANUAL'])
    expect(merged[0].actual).toBeUndefined()
    expect(merged[0].threshold).toBeUndefined()
  })

  test('builds a request from every currently loaded non-exact case', () => {
    const visual = reviewCase({id: 'visual', revision: 4})
    const exact = reviewCase({id: 'exact', origin: 'EXACT_FILE'})
    const unsupported = reviewCase({id: 'group', members: [member('a'), member('b'), member('c')]})

    expect(currentPageVerificationRequests([visual, exact, unsupported]))
      .toEqual([
        {caseId: 'visual', expectedRevision: 4},
        {caseId: 'group', expectedRevision: 1},
      ])
  })
})

describe('dedup asset URLs', () => {
  test.each([
    ['/', 'http://localhost/api/v1/books/book/thumbnail', 'http://localhost/api/v1/books/book/pages/2/thumbnail'],
    ['/komga/', 'http://localhost/komga/api/v1/books/book/thumbnail', 'http://localhost/komga/api/v1/books/book/pages/2/thumbnail'],
  ])('joins resource base %s without a protocol-relative API host', (resourceBaseUrl, expectedBook, expectedPage) => {
    jest.resetModules()
    Object.assign(window, {resourceBaseUrl})
    let urls: typeof import('@/functions/urls')
    jest.isolateModules(() => {
      urls = require('@/functions/urls')
    })

    expect(urls!.bookThumbnailUrl('book')).toBe(expectedBook)
    expect(urls!.bookPageThumbnailUrl('book', 2)).toBe(expectedPage)
  })
})

function reason(
  code: string,
  severity: DedupEligibilityReasonDto['severity'],
  appliesTo: DedupEligibilityReasonDto['appliesTo'],
  confirmationRequired: boolean,
): DedupEligibilityReasonDto {
  return {
    code,
    severity,
    appliesTo,
    confirmationRequired,
    scope: 'CASE',
    memberIds: ['left', 'right'],
    messageKey: code,
    actual: null,
    threshold: null,
    pageRanges: [],
    action: 'RUN_DEEP_VERIFICATION',
  }
}

function member(bookId: string) {
  return {book: null, bookId, activeBookCountInSeries: 1, inMvpScope: true}
}

function reviewCase(overrides: Partial<DedupReviewCaseDto> = {}): DedupReviewCaseDto {
  return {
    id: 'case',
    libraryId: 'library',
    revision: 1,
    status: 'REVIEW_REQUIRED',
    origin: 'COVER_SIMILARITY',
    relationType: 'VISUALLY_SIMILAR',
    coverDistance: 0,
    coverageLeft: null,
    coverageRight: null,
    longestMatchedRun: null,
    unmatchedPrefixCount: null,
    unmatchedSuffixCount: null,
    unmatchedInternalCount: null,
    suggestedKeeperBookId: null,
    members: [member('left'), member('right')],
    eligibility: {
      suggestedPlanEligible: false,
      manualDeleteEligible: false,
      ruleVersion: 4,
      stateRevision: 'state',
      planRevision: null,
      evaluatedAt: '2026-08-03T00:00:00Z',
      blockers: [],
      warnings: [],
      passed: [],
    },
    created: '2026-08-03T00:00:00Z',
    lastModified: '2026-08-03T00:00:00Z',
    ...overrides,
  }
}
