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
      authors: ['author one', 'author two'], tags: ['romance', 'fantasy'], pageBuckets: ['pagesize_31-70'],
      tagBuckets: ['tagsize_0-5'], genres: [], groups: ['circle'], characters: [],
    }
    return shallowMount(Panel, {propsData: {state}, mocks: {$t: (key: string, args?: object) => args ? key + JSON.stringify(args) : key}})
  }

  it('shows the actual buckets and authors, and distinguishes card tags from Gorse tags', async () => {
    const wrapper = mount()
    expect(wrapper.text()).toContain('pagesize_31-70')
    expect(wrapper.text()).toContain('author one · author two')
    ;(wrapper.vm as any).state.expanded = true
    await Vue.nextTick()
    const fields = (wrapper.vm as any).fields
    expect(fields).toContainEqual({key: 'card_tags', value: '28'})
    expect(fields).toContainEqual({key: 'content_tags', value: '2'})
    expect(fields).toContainEqual({key: 'characters', value: 'gorse_feedback.unknown'})
    expect(wrapper.text()).toContain('gorse_feedback.explanation')
    wrapper.destroy()
  })

  it('shows no feedback actions in incognito but keeps readable features', () => {
    const wrapper = mount(true)
    expect(wrapper.findAll('[data-feedback]')).toHaveLength(0)
    expect(wrapper.text()).toContain('pagesize_31-70')
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
