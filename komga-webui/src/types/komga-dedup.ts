export type DedupScanInterval = 'HOURLY' | 'EVERY_6H' | 'EVERY_12H' | 'DAILY' | 'WEEKLY'
export type DedupResolutionAction = 'KEEP' | 'DELETE'
export type DedupResolutionMode = 'SUGGESTED' | 'CUSTOM'
export type DedupResolutionState = 'PROCESSING' | 'PROCESSED' | 'NEEDS_ATTENTION' | 'PARTIALLY_COMPLETED' | 'ABANDONED'

export interface DedupLibrarySettingsDto {
  libraryId: string
  libraryName: string
  enabled: boolean
  paused: boolean
  scanInterval: DedupScanInterval
  batchSize: number
  maxDurationSeconds: number
  quietPeriodSeconds: number
  coverCandidateDistance: number
  coverTopK: number
  lastBatchDate?: string | null
  lastBatchBookCount: number
}

export interface DedupSettingsDto { libraries: DedupLibrarySettingsDto[] }

export interface DedupLibraryRunStatusDto {
  libraryId: string
  libraryName: string
  lastBatchDate?: string | null
  lastBatchBookCount: number
  nextBatchDate?: string | null
}

export interface DedupStatusDto {
  pendingScanBooks: number
  automaticVerificationPairs: number
  unresolvedClusters: number
  processedResolutions: number
  enabledLibraries: number
  libraries: DedupLibraryRunStatusDto[]
}

export interface DedupClusterCoverMemberDto { bookId: string; title?: string | null; thumbnailUrl: string }

export interface DedupClusterSummaryDto {
  id: string
  libraryId: string
  revision: number
  title?: string | null
  memberCount: number
  coverMembers: DedupClusterCoverMemberDto[]
  hasSuggestion: boolean
  lastModified: string
  lastAttemptError?: string | null
}

export interface DedupClusterMemberDto {
  bookId: string
  seriesId?: string | null
  title?: string | null
  path?: string | null
  fileSize?: number | null
  pageCount?: number | null
  thumbnailUrl: string
}

export type DedupRelationType = 'EXACT_FILE' | 'EXACT_PAGE_SEQUENCE' | 'SAME_EDITION_VARIANT' | 'CONTAINED_IN' |
  'NEAR_CONTAINED_IN' | 'PARTIAL_OVERLAP' | 'ALT_EDITION' | 'EDITION_UNCERTAIN'

export interface DedupRelationDto {
  id: string
  leftBookId: string
  rightBookId: string
  type: DedupRelationType
  status: 'VERIFIED'
  coverDistance?: number | null
  containedBookId?: string | null
  containerBookId?: string | null
  coverageLeft?: number | null
  coverageRight?: number | null
  orderConsistency?: number | null
  longestMatchedRun?: number | null
  unmatchedPrefixCount?: number | null
  unmatchedSuffixCount?: number | null
  unmatchedInternalCount?: number | null
  confidence?: number | null
  evidence?: Record<string, unknown> | null
}

export interface DedupPlanMemberDto { bookId: string; action: DedupResolutionAction }
export interface DedupPlanDto { keepCount: number; deleteCount: number; members: DedupPlanMemberDto[] }

export interface DedupClusterDetailDto {
  summary: DedupClusterSummaryDto
  members: DedupClusterMemberDto[]
  relations: DedupRelationDto[]
  suggestion?: DedupPlanDto | null
  retryResolutionId?: string | null
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

export interface DedupResolutionMemberDto {
  bookId: string
  seriesId: string
  action: DedupResolutionAction
  title: string
  path: string
  expectedSize?: number | null
  state: string
  resultCode?: string | null
  result?: Record<string, unknown> | null
  lastError?: string | null
}

export interface DedupResolutionDto {
  id: string
  clusterId: string
  clusterRevision: number
  mode: DedupResolutionMode
  state: DedupResolutionState
  actorId: string
  members: DedupResolutionMemberDto[]
  result?: Record<string, unknown> | null
  created: string
  lastModified: string
  completed?: string | null
}

export interface DedupConflictDto {
  code: string
  message: string
  resolutionId?: string | null
  partial: boolean
  resolution?: DedupResolutionDto | null
}
