import {BookDto} from '@/types/komga-books'

export type DedupScanInterval = 'HOURLY' | 'EVERY_6H' | 'EVERY_12H' | 'DAILY' | 'WEEKLY'
export type DedupClusterStatus = 'UNPROCESSED' | 'PROCESSING' | 'PROCESSED' | 'NEEDS_ATTENTION'
export type DedupEvidenceMaturity = 'COVER_ONLY' | 'PARTIAL' | 'COMPLETE'
export type DedupResolutionAction = 'KEEP' | 'DELETE'
export type DedupResolutionState = 'PROCESSING' | 'PROCESSED' | 'NEEDS_ATTENTION' | 'PARTIALLY_COMPLETED'

export interface DedupLibrarySettingsDto {
  libraryId: string
  enabled: boolean
  paused: boolean
  scanInterval: DedupScanInterval
  batchSize: number
  maxDurationSeconds: number
  quietPeriodSeconds: number
  coverCandidateDistance: number
  coverTopK: number
}

export interface DedupSettingsDto { libraries: DedupLibrarySettingsDto[] }

export interface DedupStatusDto {
  work: Record<string, number>
  clusters: Record<DedupClusterStatus, number>
  resolutions: Record<DedupResolutionState, number>
  gorseSync: Record<string, number>
  enabledLibraries: number
  pausedLibraries: number
}

export interface DedupEligibilityReasonDto {
  code: string
  severity: 'BLOCKER' | 'WARNING' | 'PASSED'
  appliesTo: string[]
  confirmationRequired: boolean
  scope: string
  memberIds: string[]
  actual?: unknown | null
  threshold?: unknown | null
  action?: string | null
}

export interface DedupEligibilityReportDto {
  suggestionPlanAvailable: boolean
  suggestionEvidenceEligible: boolean
  processingEligible: boolean
  suggestedPlanEligible: boolean
  ruleVersion: number
  stateRevision: string
  planRevision?: string | null
  evaluatedAt: string
  blockers: DedupEligibilityReasonDto[]
  warnings: DedupEligibilityReasonDto[]
  passed: DedupEligibilityReasonDto[]
}

export interface DedupClusterCoverMemberDto { bookId: string; title?: string | null; thumbnailUrl: string }

export interface DedupClusterSummaryDto {
  id: string
  libraryId: string
  revision: number
  status: DedupClusterStatus
  reviewable: boolean
  memberCount: number
  coverMembers: DedupClusterCoverMemberDto[]
  verifiedPairs: number
  totalPairs: number
  evidenceMaturity: DedupEvidenceMaturity
  suggestionPlanAvailable: boolean
  suggestedPlanEligible: boolean
  suggestedKeepCount: number
  suggestedDeleteCount: number
  reopenReason?: string | null
  lastModified: string
  processed?: string | null
}

export interface DedupClusterMemberDto {
  bookId: string
  seriesId?: string | null
  book?: BookDto | null
  title?: string | null
  path?: string | null
  fileSize?: number | null
  pageCount?: number | null
  activeBookCountInSeries: number
  inMvpScope: boolean
  localStateReasonCodes: string[]
  localState: Record<string, unknown>
  thumbnailUrl: string
}

export type DedupRelationType = 'EXACT_FILE' | 'EXACT_PAGE_SEQUENCE' | 'SAME_EDITION_VARIANT' | 'CONTAINED_IN' |
  'NEAR_CONTAINED_IN' | 'PARTIAL_OVERLAP' | 'ALT_EDITION' | 'EDITION_UNCERTAIN' | 'VISUALLY_SIMILAR' | 'UNRELATED'

export interface DedupRelationDto {
  id: string
  leftBookId: string
  rightBookId: string
  type: DedupRelationType
  status: string
  coverDistance: number | null
  containedBookId?: string | null
  containerBookId?: string | null
  coverageLeft: number | null
  coverageRight: number | null
  orderConsistency: number | null
  longestMatchedRun: number | null
  unmatchedPrefixCount: number | null
  unmatchedSuffixCount: number | null
  unmatchedInternalCount: number | null
  confidence: number | null
  evidence?: Record<string, unknown> | null
}

export interface DedupPlanMemberDto {
  bookId: string
  action: DedupResolutionAction
  keeperBookId?: string | null
  directRelationId?: string | null
}

export interface DedupPlanDto {
  revision: string
  keepCount: number
  deleteCount: number
  members: DedupPlanMemberDto[]
}

export interface DedupResolutionSummaryDto { id: string; mode: 'SUGGESTED' | 'CUSTOM'; state: DedupResolutionState; created: string; completed?: string | null }

export interface DedupClusterDetailDto {
  summary: DedupClusterSummaryDto
  stateRevision: string
  members: DedupClusterMemberDto[]
  relations: DedupRelationDto[]
  suggestedPlan?: DedupPlanDto | null
  eligibility: DedupEligibilityReportDto
  lastResolution?: DedupResolutionSummaryDto | null
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
  leftBookId: string
  rightBookId: string
  relationType: DedupRelationType
  pages: Record<string, DedupPageEvidenceDto[]>
}

export interface DedupClusterVerificationRequestDto { clusterId: string; expectedRevision: number }
export type DedupClusterVerificationStatus = 'QUEUED' | 'STALE' | 'NOT_FOUND' | 'NO_ELIGIBLE_PAIR'
export interface DedupClusterVerificationResultDto {
  clusterId: string; status: DedupClusterVerificationStatus; memberCount: number; pairCount: number
  queuedPairs: number; skippedPairs: number; failedPairs: number
}
export interface DedupBulkVerificationResultDto {
  requestedClusters: number; queuedClusters: number; staleClusters: number; failedClusters: number
  queuedPairs: number; skippedPairs: number; failedPairs: number; results: DedupClusterVerificationResultDto[]
}

export interface DedupCustomResolutionMemberDto { bookId: string; action: DedupResolutionAction; keeperBookId?: string }

export interface DedupResolutionMemberDto {
  bookId: string
  seriesId: string
  action: DedupResolutionAction
  keeperBookId?: string | null
  title: string
  path: string
  expectedSize?: number | null
  expectedArchiveHash?: string | null
  state: string
  resultCode?: string | null
  result?: unknown
  lastError?: string | null
}

export interface DedupGorseSeriesResultDto {
  seriesId: string
  state: 'CONFIRMED' | 'NOT_APPLICABLE' | 'FAILED'
  expectedHidden?: boolean | null
  error?: string | null
}

export interface DedupResolutionResultPayloadDto {
  code?: string
  message?: string
  deleted?: string[]
  kept?: string[]
  series?: Record<string, DedupGorseSeriesResultDto>
}

export interface DedupResolutionDto {
  id: string
  clusterId: string
  clusterRevision: number
  mode: 'SUGGESTED' | 'CUSTOM'
  planRevision: string
  state: DedupResolutionState
  members: DedupResolutionMemberDto[]
  result?: DedupResolutionResultPayloadDto | null
  created: string
  lastModified: string
  completed?: string | null
}

export interface DedupConflictDto {
  code: string
  message: string
  resolutionId?: string | null
  clusterState?: DedupClusterStatus | null
  partial: boolean
  resolution?: DedupResolutionDto | null
}
