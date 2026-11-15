import {countGorseTags, gorseMetadata} from '@/functions/gorse-metadata'
import {SeriesDto} from '@/types/komga-series'
import {BookDto} from '@/types/komga-books'

describe('Gorse metadata', () => {
  it('counts unique content tags using the recommendation ranking rules', () => {
    expect(countGorseTags([' Romance ', 'romance', 'Action', '', 'tagSize_20', 'PAGESIZE_50'])).toBe(2)
  })

  it('uses series totals and falls back safely when older servers omit statistics', () => {
    const series = {tagCount: 28, pagesCount: 36, metadata: {tags: ['one']}, booksMetadata: {tags: ['two']}} as SeriesDto
    expect(gorseMetadata(series)).toEqual({tags: 28, pages: 36})
    expect(gorseMetadata({...series, tagCount: undefined, pagesCount: null})).toEqual({tags: 2, pages: '—'})
  })

  it('uses individual book tags and pages and treats unanalyzed pages as unknown', () => {
    const book = {seriesId: 'series', metadata: {tags: ['one', 'ONE', 'pageSize_200']}, media: {pagesCount: 24}} as BookDto
    expect(gorseMetadata(book)).toEqual({tags: 1, pages: 24})
    expect(gorseMetadata({...book, media: {...book.media, pagesCount: 0}})).toEqual({tags: 1, pages: '—'})
  })
})
