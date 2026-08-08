import {DedupClusterSummaryDto, DedupLibrarySettingsDto, DedupPageEvidenceDto, DedupResolutionDto} from '@/types/komga-dedup'

export const DEDUP_AUTO_RESOLUTION_ACTOR = 'system:dedup-auto'

export function formatDedupBytes(value?: number | null): string {
  if (value == null || !Number.isFinite(value)) return '—'
  if (value < 1024) return `${value} B`
  const units = ['KiB', 'MiB', 'GiB', 'TiB']
  let amount = value
  let index = -1
  do { amount /= 1024; index++ } while (amount >= 1024 && index < units.length - 1)
  return `${amount >= 10 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`
}

export function resolutionCounts(value: DedupResolutionDto): {kept: number; deleted: number} {
  return {
    kept: value.members.filter(member => member.action === 'KEEP').length,
    deleted: value.members.filter(member => member.action === 'DELETE').length,
  }
}

export function customActionKey(deleteCount: number): 'dedup.keepAll' | 'dedup.applySelection' {
  return deleteCount === 0 ? 'dedup.keepAll' : 'dedup.applySelection'
}

export function pageMatchCounts(pages: DedupPageEvidenceDto[]): {exact: number; perceptual: number} {
  return {
    exact: pages.filter(page => page.exactMatch === true).length,
    perceptual: pages.filter(page => page.exactMatch === false).length,
  }
}

export function withoutDedupCluster(values: DedupClusterSummaryDto[], clusterId: string): DedupClusterSummaryDto[] {
  return values.filter(value => value.id !== clusterId)
}

export function newlyEffectiveAutoResolutionLibraries(
  current: DedupLibrarySettingsDto[],
  baseline: DedupLibrarySettingsDto[],
): DedupLibrarySettingsDto[] {
  const previous = new Map(baseline.map(value => [value.libraryId, value]))
  return current.filter(value => isAutoResolutionEffective(value) && !isAutoResolutionEffective(previous.get(value.libraryId)))
}

export function dedupActorLabelKey(actorId: string): 'dedup.autoResolutionActor' | null {
  return actorId === DEDUP_AUTO_RESOLUTION_ACTOR ? 'dedup.autoResolutionActor' : null
}

function isAutoResolutionEffective(value?: DedupLibrarySettingsDto): boolean {
  return value?.enabled === true && value.paused !== true && value.autoResolveSuggestions === true
}
