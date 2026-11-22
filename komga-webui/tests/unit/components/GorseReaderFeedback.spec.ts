import {shallowMount} from '@vue/test-utils'
import Vue from 'vue'
import Panel from '@/components/GorseReaderFeedback.vue'
import {GorseReaderFeedback} from '@/services/gorse-reader-feedback'

Vue.config.ignoredElements = [/^v-/]

describe('Reader feedback panel', () => {
  const service = {getFeedbackContext: jest.fn(), getSeriesPreference: jest.fn(), setSeriesPreference: jest.fn()}
  const mount = (incognito = false) => {
    const state = new GorseReaderFeedback('finished', 'Finished series', incognito, service, jest.fn())
    state.context = {
      seriesId: 'finished', pagesCount: 36, tagCount: 28, featuresStatus: 'AVAILABLE',
      authors: ['author one', 'author two'], tags: ['romance', 'fantasy'], displayTags: ['恋爱', 'fantasy'], pageBuckets: ['pagesize_31-70'],
      tagBuckets: ['tagsize_0-5'], genres: [], groups: ['circle'], characters: [],
    }
    return shallowMount(Panel, {propsData: {state}, mocks: {$t: (key: string, args?: object) => args ? key + JSON.stringify(args) : key}})
  }

  it('shows translated Gorse tags by default, with counts distinct from card metadata', async () => {
    const wrapper = mount()
    expect(wrapper.text()).toContain('gorse_feedback.summary{"pages":36,"tags":28}')
    expect(wrapper.text()).toContain('gorse_feedback.tags{"count":2}')
    expect(wrapper.findAll('li').wrappers.map(tag => tag.text())).toEqual(['恋爱', 'fantasy'])
    expect(wrapper.text()).not.toContain('pagesize_31-70')
    await wrapper.find('[data-feedback-details-toggle]').trigger('click')
    expect(wrapper.text()).toContain('pagesize_31-70')
    expect(wrapper.text()).toContain('author one · author two')
    expect(wrapper.text()).not.toContain('romance')
    expect(wrapper.text()).toContain('gorse_feedback.explanation')
    wrapper.destroy()
  })

  it('distinguishes an empty tag list from missing or unavailable Gorse data', async () => {
    const wrapper = mount()
    const state: GorseReaderFeedback = wrapper.props('state')
    state.context = {...state.context!, tags: [], displayTags: []}
    await Vue.nextTick()
    expect(wrapper.text()).toContain('gorse_feedback.tags{"count":0}')
    expect(wrapper.text()).toContain('gorse_feedback.no_tags')
    for (const status of ['MISSING', 'UNAVAILABLE'] as const) {
      state.context.featuresStatus = status
      state.contextFailed = true
      await Vue.nextTick()
      expect(wrapper.text()).not.toContain('gorse_feedback.no_tags')
      expect(wrapper.text()).not.toContain('gorse_feedback.tags{')
      expect(wrapper.text()).toContain('gorse_feedback.summary{"pages":36,"tags":28}')
      expect(wrapper.text()).toContain('gorse_reading.retry')
    }
    wrapper.destroy()
  })

  it('scrolls the focused tag list with the keyboard while the feedback actions stay outside it', async () => {
    const wrapper = mount()
    const tags = wrapper.find('.gorse-feedback-tags')
    Object.defineProperty(tags.element, 'clientHeight', {value: 92})
    await tags.trigger('keydown', {key: 'PageDown'})
    expect(tags.element.scrollTop).toBe(92)
    await tags.trigger('keydown', {key: 'Home'})
    expect(tags.element.scrollTop).toBe(0)
    expect(tags.find('[data-feedback]').exists()).toBe(false)
    expect(wrapper.findAll('[data-feedback]')).toHaveLength(2)
    wrapper.destroy()
  })

  it('shows no feedback actions in incognito but keeps readable features', () => {
    const wrapper = mount(true)
    expect(wrapper.findAll('[data-feedback]')).toHaveLength(0)
    expect(wrapper.text()).toContain('恋爱')
    wrapper.destroy()
  })

  it('suppresses repeated activation and closes details before the panel', async () => {
    const wrapper = mount()
    const state = (wrapper.vm as any).state
    state.expanded = true
    await wrapper.trigger('keydown', {key: 'Escape'})
    expect(state.expanded).toBe(false)
    expect(wrapper.emitted('close')).toBeUndefined()
    await wrapper.trigger('keydown', {key: 'Escape'})
    expect(wrapper.emitted('close')).toHaveLength(1)
    const held = new KeyboardEvent('keydown', {key: 'Enter', repeat: true, cancelable: true})
    ;(wrapper.vm as any).keyPressed(held)
    expect(held.defaultPrevented).toBe(true)
    const arrow = new KeyboardEvent('keydown', {key: 'ArrowDown', cancelable: true})
    ;(wrapper.vm as any).keyPressed(arrow)
    expect(arrow.defaultPrevented).toBe(true)
    const space = new KeyboardEvent('keydown', {key: ' ', cancelable: true})
    ;(wrapper.vm as any).keyPressed(space)
    expect(space.defaultPrevented).toBe(true)
    wrapper.destroy()
  })
})
