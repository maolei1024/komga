import {BookDto} from '@/types/komga-books'

export type DedupScanInterval = 'HOURLY' | 'EVERY_6H' | 'EVERY_12H' | 'DAILY' | 'WEEKLY'
export type DedupCaseOrigin = 'EXACT_FILE' | 'COVER_SIMILARITY'

export interface DedupLibrarySettingsDto {
  libraryId: string
  enabled: boolean
  paused: boolean
  scanInterval: DedupScanInterval
  batchSize: number
  maxDurationSeconds: number
  quietPeriodSeconds: number
  completionStabilitySeconds: number
  coverCandidateDistance: number
  coverTopK: number
}

export interface DedupSettingsDto {
  libraries: DedupLibrarySettingsDto[]
}

export interface DedupStatusDto {
  work: Record<string, number>
  decisions: Record<string, number>
  decisionItems: Record<string, number>
  gorseSync: Record<string, number>
  enabledLibraries: number
  pausedLibraries: number
  reviewCases: number
  exactFileCases: number
}

export interface DedupEligibilityReasonDto {
  code: string
  severity: 'BLOCKER' | 'WARNING' | 'PASSED'
  appliesTo: Array<'SUGGESTED' | 'MANUAL'>
  confirmationRequired: boolean
  scope: string
  memberIds: string[]
  messageKey: string
  actual?: unknown
  threshold?: unknown
  pageRanges: string[]
  action?: string
}

export interface DedupEligibilityReportDto {
  suggestedPlanEligible: boolean
  manualDeleteEligible: boolean
  ruleVersion: number
  stateRevision: string
  planRevision?: string
  evaluatedAt: string
  blockers: DedupEligibilityReasonDto[]
  warnings: DedupEligibilityReasonDto[]
  passed: DedupEligibilityReasonDto[]
}

export type DedupDecisionState = 'DRAFT' | 'APPROVED' | 'REVALIDATING' | 'PURGING' | 'PARTIALLY_COMPLETED' |
  'REAPPROVAL_REQUIRED' | 'COMPLETED' | 'NEEDS_ATTENTION' | 'ABORTED' | 'FAILED'

export interface DedupDecisionItemDto {
  id: string
  bookId: string
  seriesId: string
  title: string
  path: string
  expectedSize: number
  expectedArchiveHash: string
  state: string
  attemptCount: number
  resultCode?: string
  result?: unknown
  stabilityNotBefore?: string
}

export interface DedupDecisionDto {
  id: string
  reviewCaseId?: string
  planRevision: string
  mode: 'SUGGESTED' | 'MANUAL'
  keeperBookId: string
  state: DedupDecisionState
  items: DedupDecisionItemDto[]
  result?: unknown
  gorseSyncState: string
  remoteConfirmationState: string
  approved?: string
  executed?: string
  completed?: string
}

export interface DedupPageEvidenceDto {
  bookId: string
  pageNumber: number
  matchedBookId?: string
  matchedPageNumber?: number
  exactMatch?: boolean
  thumbnailUrl: string
}

export interface DedupPageComparisonDto {
  relationType: string
  pages: Record<string, DedupPageEvidenceDto[]>
}

export interface DedupReviewCaseMemberDto {
  book?: BookDto
  bookId: string
  activeBookCountInSeries: number
  inMvpScope: boolean
}

export interface DedupReviewCaseDto {
  id: string
  libraryId: string
  revision: number
  status: string
  origin: DedupCaseOrigin
  relationType: string
  coverDistance?: number
  coverageLeft?: number
  coverageRight?: number
  longestMatchedRun?: number
  unmatchedPrefixCount?: number
  unmatchedSuffixCount?: number
  unmatchedInternalCount?: number
  suggestedKeeperBookId?: string
  members: DedupReviewCaseMemberDto[]
  eligibility: DedupEligibilityReportDto
  created: string
  lastModified: string
}
