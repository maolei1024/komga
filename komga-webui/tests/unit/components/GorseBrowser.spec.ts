import {shallowMount, Wrapper} from '@vue/test-utils'
import Vue from 'vue'
import GorseBrowser from '@/components/GorseBrowser.vue'
import {GorseReadingError} from '@/services/gorse-reading'

Vue.config.ignoredElements = [/^v-/]
const flushPromises = () => new Promise(resolve => setTimeout(resolve, 0))
const series = {id: 'series', booksCount: 99, oneshot: false, metadata: {title: 'Series'}}
const book = {id: 'one', seriesId: 'series', deleted: false, media: {status: 'READY', pagesCount: 2, mediaProfile: 'DIVINA'}}

describe('Gorse series selection', () => {
  let wrapper: Wrapper<Vue> | undefined
  const session = {books: jest.fn(), recommendations: jest.fn(), prepareBook: jest.fn()}
  const openBook = jest.fn()

  beforeEach(() => {
    jest.resetAllMocks()
    session.recommendations.mockResolvedValue([series])
    session.prepareBook.mockResolvedValue({book, series, pages: []})
    openBook.mockResolvedValue(undefined)
  })
  afterEach(() => wrapper?.destroy())

  async function mount(initialSeries?: typeof series) {
    wrapper = shallowMount(GorseBrowser, {
      propsData: {session, openBook, initialSeries},
      mocks: {$t: (key: string) => key},
    })
    await flushPromises()
    return wrapper.vm as any
  }

  it('opens an actual single book regardless of oneshot or reported series count', async () => {
    session.books.mockResolvedValue([book])
    await mount(series)
    expect(openBook).toHaveBeenCalledWith({book, series, pages: []})
  })

  it('shows multiple books without automatically choosing one', async () => {
    session.books.mockResolvedValue([book, {...book, id: 'two'}])
    const view = await mount(series)
    expect(view.visibleItems).toHaveLength(2)
    expect(openBook).not.toHaveBeenCalled()
    await view.read(book)
    expect(openBook).toHaveBeenCalledTimes(1)
  })

  it('keeps unavailable series in the picker with an error', async () => {
    session.books.mockResolvedValue([{...book, deleted: true}])
    const view = await mount(series)
    expect(view.error).toBe('unavailable')
    expect(openBook).not.toHaveBeenCalled()
  })

  it('ignores double clicks while resolving a series', async () => {
    let finish!: (books: object[]) => void
    session.books.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const view = await mount()
    const pending = view.selectSeries(series)
    await view.selectSeries(series)
    expect(session.books).toHaveBeenCalledTimes(1)
    finish([book])
    await pending
    expect(openBook).toHaveBeenCalledTimes(1)
  })

  it('does not navigate when a closed picker finishes loading', async () => {
    let finish!: (books: object[]) => void
    session.books.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const view = await mount()
    const pending = view.selectSeries(series)
    wrapper!.destroy()
    finish([book])
    await pending
    expect(session.prepareBook).not.toHaveBeenCalled()
    expect(openBook).not.toHaveBeenCalled()
  })

  it('cancels a selected book if its pages or sibling metadata finish after closing', async () => {
    let finish!: (prepared: object) => void
    session.prepareBook.mockImplementation(() => new Promise(resolve => { finish = resolve }))
    const view = await mount()
    const pending = view.read(book)
    wrapper!.destroy()
    finish({book, series, pages: []})
    await pending
    expect(openBook).not.toHaveBeenCalled()
  })

  it('shows a progress failure and lets the same selection be retried', async () => {
    session.books.mockResolvedValue([book])
    openBook.mockRejectedValueOnce(new GorseReadingError('save_failed'))
    const view = await mount(series)
    expect(view.error).toBe('save_failed')
    view.retry()
    await flushPromises()
    expect(openBook).toHaveBeenCalledTimes(2)
    expect(view.error).toBe('')
  })

  it('shows recommendations in batches of twenty', async () => {
    session.recommendations.mockResolvedValue(Array.from({length: 41}, (_, index) => ({...series, id: String(index)})))
    const view = await mount()
    expect(view.visibleItems).toHaveLength(20)
    view.showMore()
    expect(view.visibleItems).toHaveLength(40)
    view.showMore()
    expect(view.visibleItems).toHaveLength(41)
  })

  it('closes the entire picker on Escape even while loading a series', async () => {
    session.books.mockResolvedValue([book, {...book, id: 'two'}])
    const view = await mount(series)
    view.busy = true
    const event = new KeyboardEvent('keydown', {key: 'Escape', cancelable: true})
    view.handleEscape(event)
    expect(event.defaultPrevented).toBe(true)
    expect(wrapper!.emitted('close')).toHaveLength(1)
  })
})
