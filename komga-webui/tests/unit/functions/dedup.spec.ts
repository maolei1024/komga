import {
  customActionKey,
  dedupActorLabelKey,
  formatDedupBytes,
  newlyEffectiveAutoResolutionLibraries,
  resolutionCounts,
  withoutDedupCluster,
} from '@/functions/dedup'
import {DedupClusterSummaryDto, DedupLibrarySettingsDto, DedupResolutionDto} from '@/types/komga-dedup'

describe('dedup helpers', () => {
  it('formats file sizes without implying precision that is not available', () => {
    expect(formatDedupBytes(null)).toBe('—')
    expect(formatDedupBytes(512)).toBe('512 B')
    expect(formatDedupBytes(1536)).toBe('1.5 KiB')
    expect(formatDedupBytes(12 * 1024 * 1024)).toBe('12 MiB')
  })

  it('counts retained and removed audit snapshots', () => {
    expect(resolutionCounts(resolution())).toEqual({kept: 2, deleted: 1})
  })

  it('uses an explicit retain-all label when no Book is marked', () => {
    expect(customActionKey(0)).toBe('dedup.keepAll')
    expect(customActionKey(2)).toBe('dedup.applySelection')
  })

  it('removes a successfully queued cluster from the current page without mutating the source', () => {
    const source = [cluster('A'), cluster('B')]

    expect(withoutDedupCluster(source, 'A').map(value => value.id)).toEqual(['B'])
    expect(source.map(value => value.id)).toEqual(['A', 'B'])
  })

  it('requires confirmation only when automatic deletion becomes effective', () => {
    const baseline = [library('A'), library('B', {enabled: false, autoResolveSuggestions: true}), library('C', {autoResolveSuggestions: true})]
    const current = [
      library('A', {autoResolveSuggestions: true}),
      library('B', {enabled: true, autoResolveSuggestions: true}),
      library('C', {autoResolveSuggestions: true}),
    ]

    expect(newlyEffectiveAutoResolutionLibraries(current, baseline).map(value => value.libraryName)).toEqual(['A', 'B'])
  })

  it('confirms when unpausing a stored automatic setting but not when disabling it', () => {
    const baseline = [
      library('paused', {paused: true, autoResolveSuggestions: true}),
      library('disabled-later', {autoResolveSuggestions: true}),
    ]
    const current = [
      library('paused', {paused: false, autoResolveSuggestions: true}),
      library('disabled-later', {enabled: false, autoResolveSuggestions: true}),
    ]

    expect(newlyEffectiveAutoResolutionLibraries(current, baseline).map(value => value.libraryName)).toEqual(['paused'])
  })

  it('uses a localized label only for the automatic system actor', () => {
    expect(dedupActorLabelKey('system:dedup-auto')).toBe('dedup.autoResolutionActor')
    expect(dedupActorLabelKey('admin')).toBeNull()
  })
})

function cluster(id: string): DedupClusterSummaryDto {
  return {
    id,
    libraryId: 'library',
    revision: 1,
    title: id,
    memberCount: 2,
    coverMembers: [],
    hasSuggestion: true,
    lastModified: '2026-08-05T00:00:00Z',
  }
}

function resolution(): DedupResolutionDto {
  return {
    id: 'resolution', clusterId: 'cluster', clusterRevision: 1, mode: 'CUSTOM', state: 'PROCESSED', actorId: 'admin',
    result: {}, created: '2026-08-05T00:00:00Z', lastModified: '2026-08-05T00:00:00Z', completed: '2026-08-05T00:00:01Z',
    members: [
      member('A', 'KEEP'), member('B', 'DELETE'), member('C', 'KEEP'),
    ],
  }
}

function member(bookId: string, action: 'KEEP' | 'DELETE') {
  return {bookId, seriesId: `series-${bookId}`, action, title: bookId, path: `/${bookId}.cbz`, state: 'COMPLETED'}
}

function library(
  id: string,
  overrides: Partial<DedupLibrarySettingsDto> = {},
): DedupLibrarySettingsDto {
  return {
    libraryId: id,
    libraryName: id,
    enabled: true,
    paused: false,
    scanInterval: 'DAILY',
    batchSize: 100,
    maxDurationSeconds: 300,
    quietPeriodSeconds: 180,
    coverCandidateDistance: 15,
    coverTopK: 20,
    autoResolveSuggestions: false,
    lastBatchDate: null,
    lastBatchBookCount: 0,
    ...overrides,
  }
}
