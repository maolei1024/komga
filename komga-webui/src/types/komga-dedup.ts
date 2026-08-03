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
  actual?: unknown | null
  threshold?: unknown | null
  pageRanges: string[]
  action?: string | null
}

export interface DedupEligibilityReportDto {
  suggestedPlanEligible: boolean
  manualDeleteEligible: boolean
  ruleVersion: number
  stateRevision: string
  planRevision?: string | null
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
  matchedBookId?: string | null
  matchedPageNumber?: number | null
  exactMatch?: boolean | null
  thumbnailUrl: string
}

export interface DedupPageComparisonDto {
  relationType: string
  pages: Record<string, DedupPageEvidenceDto[]>
}

export interface DedupReviewCaseMemberDto {
  book: BookDto | null
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
  coverDistance: number | null
  coverageLeft: number | null
  coverageRight: number | null
  longestMatchedRun: number | null
  unmatchedPrefixCount: number | null
  unmatchedSuffixCount: number | null
  unmatchedInternalCount: number | null
  suggestedKeeperBookId: string | null
  members: DedupReviewCaseMemberDto[]
  eligibility: DedupEligibilityReportDto
  created: string
  lastModified: string
}

export type DedupCaseVerificationStatus = 'QUEUED' | 'SKIPPED_EXACT_FILE' | 'STALE' | 'NOT_FOUND' | 'UNSUPPORTED_CASE'

export interface DedupCaseVerificationRequestDto {
  caseId: string
  expectedRevision: number
}

export interface DedupBulkVerificationResultDto {
  requested: number
  queued: number
  skipped: number
  stale: number
  failed: number
  results: Array<{caseId: string; status: DedupCaseVerificationStatus}>
}
