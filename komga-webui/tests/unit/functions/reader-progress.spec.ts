import {ReaderProgressWriter} from '@/functions/reader-progress'

describe('Reader progress writes', () => {
  beforeEach(() => jest.useFakeTimers())
  afterEach(() => jest.useRealTimers())

  it('keeps the book identity when a different book opens before the debounce expires', async () => {
    const save = jest.fn(async () => {})
    const writer = new ReaderProgressWriter(save, jest.fn())
    writer.queue('first-book', 12)
    writer.queue('second-book', 1)
    await writer.flush()
    expect(save.mock.calls).toEqual([['first-book', 12], ['second-book', 1]])
  })

  it('coalesces pages for a book and flushes immediately before navigation', async () => {
    const save = jest.fn(async () => {})
    const writer = new ReaderProgressWriter(save, jest.fn())
    writer.queue('book', 2)
    writer.queue('book', 3)
    await writer.flush()
    expect(save).toHaveBeenCalledTimes(1)
    expect(save).toHaveBeenCalledWith('book', 3)
  })

  it('waits for in-flight progress and serializes the final page behind it', async () => {
    let finish!: () => void
    const save = jest.fn<Promise<void>, [string, number]>()
      .mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
      .mockResolvedValue(undefined)
    const writer = new ReaderProgressWriter(save, jest.fn())
    writer.queue('book', 2)
    const first = writer.flush()
    writer.queue('book', 3)
    let completed = false
    const final = writer.flush().then(() => { completed = true })
    await Promise.resolve()
    expect(completed).toBe(false)
    expect(save).toHaveBeenCalledTimes(1)
    finish()
    await Promise.all([first, final])
    expect(save.mock.calls).toEqual([['book', 2], ['book', 3]])
  })

  it('keeps failed progress for an explicit retry', async () => {
    const save = jest.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined)
    const writer = new ReaderProgressWriter(save, jest.fn())
    writer.queue('book', 8)
    await expect(writer.flush()).rejects.toThrow('offline')
    await writer.flush()
    expect(save.mock.calls).toEqual([['book', 8], ['book', 8]])
  })

  it('reports a background failure and retries the newest page, not stale progress', async () => {
    const save = jest.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined)
    const onError = jest.fn()
    const writer = new ReaderProgressWriter(save, onError)
    writer.queue('book', 8)
    jest.advanceTimersByTime(50)
    await new Promise(resolve => jest.requireActual('timers').setImmediate(resolve))
    expect(onError).toHaveBeenCalledTimes(1)
    writer.queue('book', 10)
    await writer.flush()
    expect(save).toHaveBeenLastCalledWith('book', 10)
  })
})
