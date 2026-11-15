import {BookDto} from '@/types/komga-books'
import {SeriesDto} from '@/types/komga-series'

export function countGorseTags(tags: string[]): number {
  return new Set(tags.map(tag => tag.trim().toLowerCase())
    .filter(tag => tag && !/^(tagsize_|pagesize_)/.test(tag))).size
}

export function gorseMetadata(item: SeriesDto | BookDto): {tags: number, pages: number | string} {
  if ('seriesId' in item) return {
    tags: countGorseTags(item.metadata.tags || []),
    pages: item.media.pagesCount > 0 ? item.media.pagesCount : '—',
  }
  return {
    tags: item.tagCount ?? countGorseTags([...(item.metadata.tags || []), ...(item.booksMetadata?.tags || [])]),
    pages: item.pagesCount && item.pagesCount > 0 ? item.pagesCount : '—',
  }
}
