import {shallowMount} from '@vue/test-utils'
import PreferenceGaussianCurve from '@/components/PreferenceGaussianCurve.vue'

describe('PreferenceGaussianCurve', () => {
  it('updates a center through the keyboard without applying server data', async () => {
    const wrapper = shallowMount(PreferenceGaussianCurve, {
      stubs: ['v-row', 'v-col', 'v-text-field'],
      propsData: {value: {mean: 30, deviation: 10}, label: '页数', min: 1, max: 240},
    })
    await wrapper.findAll('circle').at(0).trigger('keydown.right')
    expect(wrapper.emitted().input?.[0]).toEqual([{mean: 31, deviation: 10}])
    expect(wrapper.emitted().change).toHaveLength(1)
  })
})
