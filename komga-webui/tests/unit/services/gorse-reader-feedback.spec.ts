import {GorseReaderFeedback} from '@/services/gorse-reader-feedback'

const deferred = <Result>() => {
  let resolve!: (result: Result) => void
  let reject!: (error: Error) => void
  const promise = new Promise<Result>((done, fail) => { resolve = done; reject = fail })
  return {promise, resolve, reject}
}

describe('Reader feedback', () => {
  const service = {getFeedbackContext: jest.fn(), getSeriesPreference: jest.fn(), setSeriesPreference: jest.fn()}
  const saveProgress = jest.fn()
  let state: GorseReaderFeedback

  beforeEach(async () => {
    jest.resetAllMocks()
    service.getFeedbackContext.mockResolvedValue({seriesId: 'series', featuresStatus: 'AVAILABLE', tags: ['a']})
    service.getSeriesPreference.mockResolvedValue({seriesId: 'series', preference: 'NONE'})
    service.setSeriesPreference.mockImplementation(async (seriesId, preference) => ({seriesId, preference}))
    saveProgress.mockResolvedValue(undefined)
    state = new GorseReaderFeedback('series', 'Finished series', false, service, saveProgress)
    await state.load()
  })

  it('waits for final progress and serializes repeated clicks before feedback', async () => {
    const progress = deferred<void>()
    saveProgress.mockReturnValue(progress.promise)
    const pending = state.toggle('LIKE')
    await state.toggle('DISLIKE')
    expect(state.saving).toBe(true)
    expect(service.setSeriesPreference).not.toHaveBeenCalled()
    progress.resolve()
    await pending
    expect(service.setSeriesPreference).toHaveBeenCalledTimes(1)
    expect(service.setSeriesPreference).toHaveBeenCalledWith('series', 'LIKE')
    expect(state.preference).toBe('LIKE')
    expect(state.saved).toBe(true)
  })

  it('switches and cancels preferences using the existing contract', async () => {
    await state.toggle('LIKE')
    await state.toggle('DISLIKE')
    await state.toggle('DISLIKE')
    expect(service.setSeriesPreference.mock.calls.map(call => call[1])).toEqual(['LIKE', 'DISLIKE', 'NONE'])
    expect(state.preference).toBe('NONE')
  })

  it('retries the same target after a failed or ambiguous response', async () => {
    service.setSeriesPreference.mockRejectedValueOnce(new Error('response lost'))
    await state.toggle('LIKE')
    expect(state.saveError).toBe('feedback_failed')
    expect(state.retryTarget).toBe('LIKE')
    await state.retry()
    expect(service.setSeriesPreference.mock.calls).toEqual([['series', 'LIKE'], ['series', 'LIKE']])
    expect(state.saveError).toBe('')
  })

  it('does not submit feedback if progress fails and retries saving first', async () => {
    saveProgress.mockRejectedValueOnce(new Error('offline'))
    await state.toggle('DISLIKE')
    expect(service.setSeriesPreference).not.toHaveBeenCalled()
    expect(state.saveError).toBe('progress_failed')
    await state.retry()
    expect(saveProgress).toHaveBeenCalledTimes(2)
    expect(service.setSeriesPreference).toHaveBeenCalledWith('series', 'DISLIKE')
  })

  it('disables feedback until preference loading succeeds, independently of feature failure', async () => {
    service.getSeriesPreference.mockRejectedValueOnce(new Error('offline'))
    service.getFeedbackContext.mockResolvedValueOnce({featuresStatus: 'UNAVAILABLE'})
    await state.load()
    await state.toggle('LIKE')
    expect(state.contextFailed).toBe(true)
    expect(state.preferenceFailed).toBe(true)
    expect(service.setSeriesPreference).not.toHaveBeenCalled()
    await state.loadPreference()
    await state.toggle('LIKE')
    expect(state.preference).toBe('LIKE')
  })

  it('keeps obsolete feature and preference responses out of the next series', async () => {
    const context = deferred<any>()
    const preference = deferred<any>()
    service.getFeedbackContext.mockReturnValueOnce(context.promise)
    service.getSeriesPreference.mockReturnValueOnce(preference.promise)
    const pending = state.load()
    state.dispose()
    const next = new GorseReaderFeedback('next', 'Next series', false, service, saveProgress)
    await next.load()
    context.resolve({seriesId: 'old', featuresStatus: 'MISSING'})
    preference.resolve({preference: 'DISLIKE'})
    await pending
    expect(state.preference).toBe('NONE')
    expect(state.context?.seriesId).toBe('series')
    expect(next.preference).toBe('NONE')
  })

  it('does not send a pending choice if the reader is disposed during progress saving', async () => {
    const progress = deferred<void>()
    saveProgress.mockReturnValueOnce(progress.promise)
    const pending = state.toggle('LIKE')
    state.dispose()
    progress.resolve()
    await pending
    expect(service.setSeriesPreference).not.toHaveBeenCalled()
  })

  it('ignores a late save response after switching objects', async () => {
    const response = deferred<any>()
    service.setSeriesPreference.mockReturnValueOnce(response.promise)
    const pending = state.toggle('LIKE')
    await Promise.resolve()
    state.dispose()
    response.resolve({preference: 'LIKE'})
    await pending
    expect(state.preference).toBe('NONE')
  })

  it('only loads features in incognito and never submits or saves progress', async () => {
    service.getSeriesPreference.mockClear()
    const incognito = new GorseReaderFeedback('series', 'Series', true, service, saveProgress)
    await incognito.load()
    await incognito.toggle('LIKE')
    expect(incognito.context).not.toBeNull()
    expect(service.getSeriesPreference).not.toHaveBeenCalled()
    expect(service.setSeriesPreference).not.toHaveBeenCalled()
    expect(saveProgress).not.toHaveBeenCalled()
  })
})
