import type KomgaBooksService from '@/services/komga-books.service'
import type KomgaSeriesService from '@/services/komga-series.service'
import {BookDto, PageDto} from '@/types/komga-books'
import {SeriesDto} from '@/types/komga-series'
import {SearchConditionSeriesId, SearchOperatorIs} from '@/types/komga-search'
import {getBookReadRouteFromMedia} from '@/functions/book-format'

export const GORSE_BATCH_SIZE = 20

export class GorseReadingError extends Error {
  constructor(public readonly key: string) {
    super(key)
  }
}

export interface PreparedGorseBook {
  book: BookDto,
  series: SeriesDto,
  pages: PageDto[],
  nextBook?: BookDto,
  previousBook?: BookDto,
}

export function isGorseReadable(book: BookDto): boolean {
  return !book.deleted && book.media.status === 'READY' && book.media.pagesCount > 0 && getBookReadRouteFromMedia(book.media) === 'read-book'
}

export class GorseReadingSession {
  private candidates: SeriesDto[] = []
  private opened = new Set<string>()
  private loaded = false
  private automaticallyRefreshed = false
  private loading: Promise<void> | undefined
  private prepared: PreparedGorseBook | undefined

  constructor(
    private readonly seriesService: Pick<KomgaSeriesService, 'getRecommendedSeries' | 'getOneSeries'>,
    private readonly booksService: Pick<KomgaBooksService, 'getBooksList' | 'getBook' | 'getBookPages' | 'getBookSiblingNext' | 'getBookSiblingPrevious'>,
  ) {}

  private async load(): Promise<void> {
    if (this.loading) return this.loading
    this.loading = (async () => {
      const page = await this.seriesService.getRecommendedSeries({page: 0, size: 100})
      const unique = new Map<string, SeriesDto>()
      page.content.forEach(series => {
        if (!series.deleted && !unique.has(series.id)) unique.set(series.id, series)
      })
      this.candidates = [...unique.values()]
      this.loaded = true
    })()
    try {
      await this.loading
    } finally {
      this.loading = undefined
    }
  }

  async recommendations(refresh = false): Promise<SeriesDto[]> {
    const wasLoaded = this.loaded
    if (refresh || !this.loaded) await this.load()
    let remaining = this.candidates.filter(series => !this.opened.has(series.id))
    if (wasLoaded && !refresh && !remaining.length && !this.automaticallyRefreshed) {
      this.automaticallyRefreshed = true
      await this.load()
      remaining = this.candidates.filter(series => !this.opened.has(series.id))
    }
    return remaining
  }

  openedBook(book: BookDto) {
    if (!this.opened.has(book.seriesId)) this.automaticallyRefreshed = false
    this.opened.add(book.seriesId)
  }

  async books(seriesId: string): Promise<BookDto[]> {
    const books = new Map<string, BookDto>()
    let pageNumber = 0
    while (true) {
      const page = await this.booksService.getBooksList({
        condition: new SearchConditionSeriesId(new SearchOperatorIs(seriesId)),
      }, {page: pageNumber, size: 100, sort: ['metadata.numberSort,asc']})
      page.content.forEach(book => {
        if (!book.deleted) books.set(book.id, book)
      })
      if (page.last || !page.content.length) break
      pageNumber++
    }
    return [...books.values()]
  }

  async prepareBook(bookId: string): Promise<PreparedGorseBook> {
    const book = await this.booksService.getBook(bookId)
    if (!isGorseReadable(book)) throw new GorseReadingError('unavailable')
    const sibling = async (request: Promise<BookDto>): Promise<BookDto | undefined> => {
      try {
        return await request
      } catch (error) {
        if (error.status !== 404) throw error
        return undefined
      }
    }
    const [series, pages, nextBook, previousBook] = await Promise.all([
      this.seriesService.getOneSeries(book.seriesId),
      this.booksService.getBookPages(book.id),
      sibling(this.booksService.getBookSiblingNext(book.id)),
      sibling(this.booksService.getBookSiblingPrevious(book.id)),
    ])
    if (series.deleted || !pages.length) throw new GorseReadingError('unavailable')
    this.prepared = {book, series, pages, nextBook, previousBook}
    return this.prepared
  }

  takePrepared(bookId: string): PreparedGorseBook | undefined {
    const prepared = this.prepared?.book.id === bookId ? this.prepared : undefined
    this.prepared = undefined
    return prepared
  }
}

let active: {userId: string, session: GorseReadingSession} | undefined

export function resetGorseReading() {
  active = undefined
}

export function getGorseReading(
  userId: string,
  series: ConstructorParameters<typeof GorseReadingSession>[0],
  books: ConstructorParameters<typeof GorseReadingSession>[1],
  restart = false,
): GorseReadingSession {
  if (restart || !active || active.userId !== userId) active = {userId, session: new GorseReadingSession(series, books)}
  return active.session
}
