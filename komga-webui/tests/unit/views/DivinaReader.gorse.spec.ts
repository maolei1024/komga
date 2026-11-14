import {shallowMount, Wrapper} from '@vue/test-utils'
import Vue from 'vue'
import lodash from 'lodash'
import DivinaReader from '@/views/DivinaReader.vue'
import {ContinuousScaleType, PagedReaderLayout, ScaleType} from '@/types/enum-reader'
import {ReadingDirection} from '@/types/enum-books'
import {resetGorseReading} from '@/services/gorse-reading'
import {isReaderAtBottom} from '@/functions/reader-boundary'

jest.mock('screenfull', () => ({isEnabled: false}))
jest.mock('@/functions/check-image')
jest.mock('@/i18n', () => ({__esModule: true, default: {t: (key: string) => key}}))
jest.mock('@/functions/reader-boundary', () => ({
  isReaderAtBottom: jest.fn(() => true),
  isTyping: jest.fn(() => false),
}))

Vue.config.ignoredElements = [/^v-/]
const flushPromises = () => new Promise(resolve => setTimeout(resolve, 0))
const book = (id = 'first') => ({
  id, seriesId: 'series', oneshot: false, deleted: false,
  metadata: {title: id, number: '1'},
  media: {status: 'READY', pagesCount: 2, mediaProfile: 'DIVINA'},
})

describe('Gorse reader transitions', () => {
  let wrapper: Wrapper<Vue> | undefined
  const books = {
    getBook: jest.fn(), getBookPages: jest.fn(), getBookSiblingNext: jest.fn(), getBookSiblingPrevious: jest.fn(),
    updateReadProgress: jest.fn(), getBooksList: jest.fn(),
  }
  const series = {getOneSeries: jest.fn(), getRecommendedSeries: jest.fn()}
  const router = {replace: jest.fn(), push: jest.fn()}

  beforeEach(() => {
    jest.clearAllMocks()
    resetGorseReading()
    ;(isReaderAtBottom as jest.Mock).mockReturnValue(true)
    books.getBook.mockImplementation(async id => book(id))
    books.getBookPages.mockResolvedValue([{number: 1, mediaType: 'image/png'}, {number: 2, mediaType: 'image/png'}])
    books.getBookSiblingNext.mockRejectedValue({status: 404})
    books.getBookSiblingPrevious.mockRejectedValue({status: 404})
    books.updateReadProgress.mockResolvedValue(undefined)
    series.getOneSeries.mockResolvedValue({id: 'series', metadata: {title: 'Series'}})
    series.getRecommendedSeries.mockResolvedValue({content: []})
    router.replace.mockResolvedValue(undefined)
    router.push.mockResolvedValue(undefined)
  })
  afterEach(async () => {
    wrapper?.destroy()
    await flushPromises()
  })

  async function mount(query: Record<string, string> = {mode: 'gorse'}) {
    wrapper = shallowMount(DivinaReader, {
      propsData: {bookId: 'first'},
      mocks: {
        $_: lodash,
        $debug: jest.fn(),
        $t: (key: string) => key,
        $i18n: {t: (key: string) => key},
        $route: {name: 'read-book', params: {bookId: 'first'}, query},
        $router: router,
        $komgaBooks: books,
        $komgaSeries: series,
        $vuetify: {rtl: false, breakpoint: {width: 1200, height: 800}},
        $store: {
          getters: {mePageStreaming: true},
          commit: jest.fn(),
          state: {
            komgaUsers: {me: {id: 'user'}},
            persistedState: {webreader: {
              readingDirection: ReadingDirection.WEBTOON, animations: false, swipe: false, alwaysFullscreen: false,
              background: 'black',
              paged: {scale: ScaleType.SCREEN, pageLayout: PagedReaderLayout.SINGLE_PAGE},
              continuous: {scale: ContinuousScaleType.WIDTH, padding: 0, margin: 0},
            }},
          },
        },
      },
    })
    await flushPromises()
    return wrapper.vm as any
  }

  it('opens recommendations at the series end only after progress is saved', async () => {
    const view = await mount()
    await view.runReaderAction(view.continueGorse)
    expect(books.updateReadProgress).toHaveBeenLastCalledWith('first', {page: 2})
    expect(view.gorsePicker).toBe(true)
    expect(router.push).not.toHaveBeenCalled()
  })

  it('continues the next book in the series with the Gorse mode intact', async () => {
    books.getBookSiblingNext.mockResolvedValue(book('second'))
    const view = await mount()
    await view.runReaderAction(view.continueGorse)
    expect(router.replace).toHaveBeenLastCalledWith({name: 'read-book', params: {bookId: 'second'}, query: {mode: 'gorse', incognito: 'false'}})
    expect(view.gorsePicker).toBe(false)
  })

  it('keeps the current book when final progress fails and lets the transition be retried', async () => {
    books.getBookSiblingNext.mockResolvedValue(book('second'))
    const view = await mount()
    router.replace.mockClear()
    books.updateReadProgress.mockRejectedValueOnce(new Error('offline'))
    await view.runReaderAction(view.continueGorse)
    expect(router.replace).not.toHaveBeenCalled()
    expect(view.book.id).toBe('first')
    expect(view.readerError).toBe('save_failed')
    view.retryReader()
    await flushPromises()
    expect(router.replace).toHaveBeenCalledWith(expect.objectContaining({params: {bookId: 'second'}}))
  })

  it('does not advance until the actual bottom or on a repeated held key', async () => {
    const view = await mount()
    const advance = jest.spyOn(view, 'jumpToNext')
    ;(isReaderAtBottom as jest.Mock).mockReturnValue(false)
    view.gorseKeyPressed(new KeyboardEvent('keydown', {key: 'ArrowDown'}))
    expect(advance).not.toHaveBeenCalled()
    ;(isReaderAtBottom as jest.Mock).mockReturnValue(true)
    view.gorseKeyPressed(new KeyboardEvent('keydown', {key: 'ArrowDown', repeat: true}))
    expect(advance).not.toHaveBeenCalled()
    view.gorseKeyPressed(new KeyboardEvent('keydown', {key: 'ArrowDown'}))
    expect(advance).toHaveBeenCalledTimes(1)
    await flushPromises()
  })

  it('pauses the reader keyboard while a picker is open', async () => {
    const view = await mount()
    view.gorsePicker = true
    const advance = jest.spyOn(view, 'jumpToNext')
    const close = jest.spyOn(view, 'closeDialog')
    view.gorseKeyPressed(new KeyboardEvent('keydown', {key: 'ArrowDown'}))
    view.keyPressed(new KeyboardEvent('keydown', {key: 'Escape'}))
    expect(advance).not.toHaveBeenCalled()
    expect(close).not.toHaveBeenCalled()
    expect(view.gorseInputBlocked).toBe(true)
  })

  it('binds page URLs and progress to the loaded book before route props update', async () => {
    const view = await mount()
    await view.flushProgress()
    await view.setup('second', 2, {query: {mode: 'gorse'}, params: {bookId: 'second'}})
    await view.flushProgress()
    expect(view.pages[0].url).toContain('/books/second/pages/1')
    expect(books.updateReadProgress).toHaveBeenLastCalledWith('second', {page: 2})
    expect(view.bookId).toBe('first')
  })

  it('preserves incognito progress semantics', async () => {
    const view = await mount({mode: 'gorse', incognito: 'true'})
    await view.runReaderAction(view.continueGorse)
    expect(books.updateReadProgress).not.toHaveBeenCalled()
    expect(view.gorsePicker).toBe(true)
  })

  it('retains the confirmation step in ordinary reading', async () => {
    const view = await mount({})
    view.jumpToNext()
    expect(view.jumpToNextBook).toBe(true)
    expect(view.gorsePicker).toBe(false)
    expect(router.push).not.toHaveBeenCalled()
  })

  it('shows a recoverable error rather than an empty reader for missing pages', async () => {
    books.getBookPages.mockResolvedValue([])
    const view = await mount()
    expect(view.readerError).toBe('unavailable')
    expect(wrapper!.text()).toContain('gorse_reading.retry')
    expect(books.updateReadProgress).not.toHaveBeenCalled()
  })

  it('allows a logout redirect even if progress cannot be saved', async () => {
    const view = await mount()
    const save = jest.spyOn(view, 'flushProgress').mockRejectedValue(new Error('unauthorized'))
    const next = jest.fn()
    const hook = (DivinaReader as any).options.beforeRouteLeave
    await (Array.isArray(hook) ? hook[0] : hook).call(view, {name: 'login'}, {}, next)
    expect(next).toHaveBeenCalledWith()
    expect(save).not.toHaveBeenCalled()
  })
})
