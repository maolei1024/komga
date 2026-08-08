import {shallowMount, Wrapper} from '@vue/test-utils'
import Vue from 'vue'
import DedupLazyImage from '@/components/dedup/DedupLazyImage.vue'

describe('DedupLazyImage', () => {
  let callback!: IntersectionObserverCallback
  let wrapper: Wrapper<Vue> | null = null
  const originalIntersectionObserver = window.IntersectionObserver

  beforeEach(() => {
    window.IntersectionObserver = class {
      constructor(value: IntersectionObserverCallback) { callback = value }
      disconnect() {}
      observe() {}
      takeRecords(): IntersectionObserverEntry[] { return [] }
      unobserve() {}
      readonly root = null
      readonly rootMargin = ''
      readonly thresholds = []
    } as any
  })

  afterEach(() => {
    wrapper?.destroy()
    wrapper = null
    window.IntersectionObserver = originalIntersectionObserver
  })

  it('renders a native image with its source and accessible text after entering the preload area', async () => {
    wrapper = shallowMount(DedupLazyImage, {
      propsData: {src: '/thumbnail', alt: 'Page 1', width: 72, height: 104, highPriority: true},
    })

    expect(wrapper.find('img').exists()).toBe(false)
    callback([{isIntersecting: true}] as IntersectionObserverEntry[], {} as IntersectionObserver)
    await wrapper.vm.$nextTick()

    const image = wrapper.find('img')
    expect(image.exists()).toBe(true)
    expect(image.attributes('src')).toBe('/thumbnail')
    expect(image.attributes('alt')).toBe('Page 1')
    expect(image.attributes('decoding')).toBe('async')
    expect(image.classes()).toContain('lazy-content--contain')
  })

  it('returns to the neutral placeholder after an image error', async () => {
    wrapper = shallowMount(DedupLazyImage, {
      propsData: {src: '/missing', alt: '', width: 46, height: 68},
    })
    callback([{isIntersecting: true}] as IntersectionObserverEntry[], {} as IntersectionObserver)
    await wrapper.vm.$nextTick()

    await wrapper.find('img').trigger('error')

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('.lazy-placeholder').exists()).toBe(true)
  })
})
