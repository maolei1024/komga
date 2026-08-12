import {shallowMount, Wrapper} from '@vue/test-utils'
import Vue from 'vue'
import GorseLikeButton from '@/components/GorseLikeButton.vue'

const flushPromises = () => new Promise(resolve => setTimeout(resolve, 0))

describe('GorseLikeButton', () => {
  let wrapper: Wrapper<Vue> | null = null
  let preference = 'NONE'
  let rejectUpdate = false
  const eventHub = {$emit: jest.fn()}
  const service = {
    getSeriesPreference: jest.fn(async () => ({seriesId: 'series', preference})),
    getBookPreference: jest.fn(async () => ({seriesId: 'series', preference})),
    setSeriesPreference: jest.fn(async (_id: string, next: string) => {
      if (rejectUpdate) throw new Error('remote failure')
      preference = next
      return {seriesId: 'series', preference}
    }),
    setBookPreference: jest.fn(async (_id: string, next: string) => {
      if (rejectUpdate) throw new Error('remote failure')
      preference = next
      return {seriesId: 'series', preference}
    }),
  }

  beforeEach(() => {
    preference = 'NONE'
    rejectUpdate = false
    jest.clearAllMocks()
  })

  afterEach(() => {
    wrapper?.destroy()
    wrapper = null
  })

  function mount(propsData: Record<string, string> = {seriesId: 'series'}) {
    wrapper = shallowMount(GorseLikeButton, {
      propsData,
      mocks: {$komgaGorse: service, $eventHub: eventHub},
      stubs: {
        'v-tooltip': {template: '<div><slot name="activator" :on="{}"/><slot/></div>'},
        'v-btn': {template: '<button v-bind="$attrs" v-on="$listeners"><slot/></button>'},
        'v-icon': {template: '<i><slot/></i>'},
      },
    })
    return wrapper
  }

  it('renders mutually exclusive like and dislike controls and supports direct switching and cancellation', async () => {
    const view = mount()
    await flushPromises()

    expect(view.find('[data-testid="gorse-like"]').exists()).toBe(true)
    expect(view.find('[data-testid="gorse-dislike"]').exists()).toBe(true)

    await view.find('[data-testid="gorse-like"]').trigger('click')
    await flushPromises()
    expect((view.vm as any).preference).toBe('LIKE')

    await view.find('[data-testid="gorse-dislike"]').trigger('click')
    await flushPromises()
    expect((view.vm as any).preference).toBe('DISLIKE')

    await view.find('[data-testid="gorse-dislike"]').trigger('click')
    await flushPromises()
    expect((view.vm as any).preference).toBe('NONE')
    expect(service.setSeriesPreference.mock.calls.map(call => call[1])).toEqual(['LIKE', 'DISLIKE', 'NONE'])
  })

  it('uses the book endpoint and keeps the old state when an update fails', async () => {
    preference = 'LIKE'
    const view = mount({bookId: 'book'})
    await flushPromises()
    rejectUpdate = true

    await view.find('[data-testid="gorse-dislike"]').trigger('click')
    await flushPromises()

    expect(service.getBookPreference).toHaveBeenCalledWith('book')
    expect((view.vm as any).preference).toBe('LIKE')
    expect(eventHub.$emit).toHaveBeenCalledWith('error', {message: '更新 Gorse 偏好失败'})
  })

  it('shares loading state between both controls and ignores duplicate clicks', async () => {
    let finishUpdate: ((value: {seriesId: string; preference: string}) => void) | undefined
    service.setSeriesPreference.mockImplementationOnce((_id: string, next: string) => new Promise(resolve => {
      finishUpdate = resolve
      preference = next
    }))
    const view = mount()
    await flushPromises()

    await view.find('[data-testid="gorse-like"]').trigger('click')
    await view.find('[data-testid="gorse-dislike"]').trigger('click')

    expect((view.vm as any).loading).toBe(true)
    expect(service.setSeriesPreference).toHaveBeenCalledTimes(1)
    finishUpdate?.({seriesId: 'series', preference: 'LIKE'})
    await flushPromises()
    expect((view.vm as any).preference).toBe('LIKE')
    expect((view.vm as any).loading).toBe(false)
  })
})
