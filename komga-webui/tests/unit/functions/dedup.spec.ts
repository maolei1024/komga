import {customActionKey, formatDedupBytes, resolutionCounts} from '@/functions/dedup'
import {DedupResolutionDto} from '@/types/komga-dedup'

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
})

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
