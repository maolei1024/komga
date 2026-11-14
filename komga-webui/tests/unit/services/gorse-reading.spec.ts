import {BookDto, PageDto} from '@/types/komga-books'
import {SeriesDto} from '@/types/komga-series'
import {getGorseReading, GorseReadingSession, isGorseReadable, resetGorseReading} from '@/services/gorse-reading'

const series = (id: string) => ({id, deleted: false, booksCount: 1} as SeriesDto)
const book = (id: string, seriesId = 'series-1') => ({
  id, seriesId, deleted: false,
  media: {status: 'READY', pagesCount: 2, mediaProfile: 'DIVINA'},
} as BookDto)

describe('Gorse reading session', () => {
  const seriesService = {
    getRecommendedSeries: jest.fn(),
    getOneSeries: jest.fn(),
  }
  const booksService = {
    getBooksList: jest.fn(),
    getBook: jest.fn(),
    getBookPages: jest.fn(),
    getBookSiblingNext: jest.fn(),
    getBookSiblingPrevious: jest.fn(),
  }
  let session: GorseReadingSession

  beforeEach(() => {
    jest.resetAllMocks()
    resetGorseReading()
    session = new GorseReadingSession(seriesService, booksService)
    booksService.getBookSiblingNext.mockRejectedValue({status: 404})
    booksService.getBookSiblingPrevious.mockRejectedValue({status: 404})
    seriesService.getRecommendedSeries.mockResolvedValue({content: [series('series-1'), series('series-2')]})
  })

  it('deduplicates the snapshot in recommendation order and excludes opened series', async () => {
    seriesService.getRecommendedSeries.mockResolvedValue({content: [series('series-1'), series('series-1'), series('series-2'), {...series('deleted'), deleted: true}]})
    expect((await session.recommendations()).map(item => item.id)).toEqual(['series-1', 'series-2'])
    session.openedBook(book('first'))
    expect((await session.recommendations()).map(item => item.id)).toEqual(['series-2'])
    expect(seriesService.getRecommendedSeries).toHaveBeenCalledTimes(1)
    expect(seriesService.getRecommendedSeries).toHaveBeenCalledWith({page: 0, size: 100})
  })

  it('coalesces simultaneous snapshot requests', async () => {
    await Promise.all([session.recommendations(), session.recommendations()])
    expect(seriesService.getRecommendedSeries).toHaveBeenCalledTimes(1)
  })

  it('refreshes an exhausted snapshot once without repeatedly fetching identical recommendations', async () => {
    await session.recommendations()
    session.openedBook(book('first', 'series-1'))
    session.openedBook(book('second', 'series-2'))
    expect(await session.recommendations()).toEqual([])
    expect(await session.recommendations()).toEqual([])
    expect(seriesService.getRecommendedSeries).toHaveBeenCalledTimes(2)
    seriesService.getRecommendedSeries.mockResolvedValue({content: [series('series-3')]})
    expect((await session.recommendations(true))[0].id).toBe('series-3')
  })

  it('allows retrying a failed fetch', async () => {
    seriesService.getRecommendedSeries.mockRejectedValueOnce(new Error('offline'))
    await expect(session.recommendations()).rejects.toThrow('offline')
    expect(await session.recommendations()).toHaveLength(2)
  })

  it('resolves actual non-deleted books across all pages in number order', async () => {
    booksService.getBooksList
      .mockResolvedValueOnce({content: [book('one'), {...book('deleted'), deleted: true}], last: false})
      .mockResolvedValueOnce({content: [book('two')], last: true})
    expect((await session.books('series-1')).map(item => item.id)).toEqual(['one', 'two'])
    expect(booksService.getBooksList).toHaveBeenNthCalledWith(2, expect.anything(), {page: 1, size: 100, sort: ['metadata.numberSort,asc']})
  })

  it('validates a selected book and prepares its pages before navigation', async () => {
    booksService.getBook.mockResolvedValue(book('first'))
    booksService.getBookPages.mockResolvedValue([{number: 1}, {number: 2}] as PageDto[])
    seriesService.getOneSeries.mockResolvedValue(series('series-1'))
    const prepared = await session.prepareBook('first')
    expect(prepared.pages).toHaveLength(2)
    expect(booksService.getBookSiblingNext).toHaveBeenCalledWith('first')
    expect(session.takePrepared('first')).toEqual(prepared)
    expect(session.takePrepared('first')).toBeUndefined()
  })

  it('rejects unavailable or unsupported books without loading pages', async () => {
    const unavailable = {...book('first'), media: {...book('first').media, mediaProfile: 'EPUB', epubDivinaCompatible: false}}
    booksService.getBook.mockResolvedValue(unavailable)
    await expect(session.prepareBook('first')).rejects.toMatchObject({key: 'unavailable'})
    expect(booksService.getBookPages).not.toHaveBeenCalled()
    expect(isGorseReadable({...book('deleted'), deleted: true})).toBe(false)
  })

  it('does not share a reading session across users or explicit new entries', () => {
    const first = getGorseReading('user-1', seriesService, booksService)
    expect(getGorseReading('user-1', seriesService, booksService)).toBe(first)
    expect(getGorseReading('user-2', seriesService, booksService)).not.toBe(first)
    expect(getGorseReading('user-1', seriesService, booksService, true)).not.toBe(first)
    resetGorseReading()
    expect(getGorseReading('user-1', seriesService, booksService)).not.toBe(first)
  })
})
