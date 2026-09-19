import {shallowMount, Wrapper} from '@vue/test-utils'
import Vue from 'vue'
import Editor from '@/components/MetadataEnrichmentBucketEditor.vue'
import {BUCKET_INT_MAX, validBuckets} from '@/functions/metadata-enrichment-buckets'

Vue.config.ignoredElements = [/^v-/]

const initial = () => [
  {min: 1, max: 10, label: 'pageSize_1-10'},
  {min: 11, max: 30, label: 'pageSize_11-30'},
  {min: 31, max: null, label: 'pageSize_30+'},
]

describe('MetadataEnrichmentBucketEditor', () => {
  let wrapper: Wrapper<Vue>
  let vm: any
  function mount(buckets = initial(), extra = {}) {
    wrapper = shallowMount(Editor, {propsData: {buckets, start: 1, prefix: 'pageSize_', defaultWidth: 10, ...extra}})
    vm = wrapper.vm
    return buckets
  }
  afterEach(() => wrapper.destroy())
  const values = () => vm.rows.map(({min, max, label}: any) => ({min, max, label}))

  it('reproduces the requested fine grained configuration without mutating props', async () => {
    const original = mount()
    vm.append()
    vm.editEnd(2, '40')
    vm.commitEnd(2)
    vm.append()
    vm.append()
    expect(values()).toEqual([
      ...initial().slice(0, 2),
      {min: 31, max: 40, label: 'pageSize_31-40'},
      {min: 41, max: 50, label: 'pageSize_41-50'},
      {min: 51, max: 60, label: 'pageSize_51-60'},
      {min: 61, max: null, label: 'pageSize_60+'},
    ])
    expect(original).toEqual(initial())
    const id = vm.rows[2].id
    await wrapper.setProps({buckets: vm.emittedBuckets})
    expect(vm.rows[2].id).toBe(id)
    expect(vm.valid).toBe(true)
  })

  it('splits at the midpoint, retains custom names and keeps later boundaries', () => {
    mount()
    vm.editLabel(1, 'pageSize_custom')
    const id = vm.rows[1].id
    vm.split(1)
    expect(vm.rows[1]).toMatchObject({id, min: 11, max: 20, label: 'pageSize_custom', auto: false})
    expect(vm.rows[2]).toMatchObject({min: 21, max: 30, label: 'pageSize_21-30', auto: true})
    vm.editEnd(1, '18')
    expect(vm.valid).toBe(false)
    vm.commitEnd(1)
    expect(vm.rows[2]).toMatchObject({min: 19, max: 30, label: 'pageSize_19-30'})
    expect(vm.rows[3].min).toBe(31)
    vm.restoreLabel(1)
    expect(vm.rows[1].label).toBe('pageSize_11-18')
  })

  it.each([0, 1, 2])('merges deletion of row %s and preserves the absorbing row identity', (index) => {
    mount()
    const absorbing = vm.rows[index === 2 ? 1 : index + 1]
    vm.editLabel(index === 2 ? 1 : index + 1, 'pageSize_kept')
    const id = absorbing.id
    vm.remove(index)
    expect(vm.rows).toHaveLength(2)
    expect(vm.rows.find((row: any) => row.id === id).label).toBe('pageSize_kept')
    expect(validBuckets(values(), 1, 'pageSize_')).toBe(true)
    if (index === 0) expect(vm.rows[0].min).toBe(1)
    if (index === 1) expect(vm.rows[1].min).toBe(11)
    if (index === 2) expect(vm.rows[1].max).toBeNull()
  })

  it.each(['', '1.5', '-1', '30', '2147483648'])('keeps invalid draft %s without changing boundaries', (draft) => {
    mount()
    vm.editEnd(0, draft)
    vm.commitEnd(0)
    expect(vm.rows[0].end).toBe(draft)
    expect(vm.rows[0].max).toBe(10)
    expect(vm.rows[1].min).toBe(11)
    expect(vm.valid).toBe(false)
    expect(vm.endError(0)).toContain('1 至 29')
    expect(wrapper.emitted('validity')!.slice(-1)[0]).toEqual([false])
  })

  it('validates duplicate names and prefixes, and does not hide a pending boundary', () => {
    mount()
    vm.editLabel(0, 'wrong')
    expect(vm.valid).toBe(false)
    vm.editLabel(0, 'pageSize_11-30')
    expect(vm.errors[0]).toBe('标签不能重复')
    vm.restoreLabel(0)
    vm.editEnd(0, '9')
    vm.editLabel(0, 'pageSize_custom')
    expect(wrapper.emitted('validity')!.slice(-1)[0]).toEqual([false])
    vm.commitEnd(0)
    expect(vm.valid).toBe(true)
  })

  it('uses defaults for a single bucket and protects single values and the integer limit', () => {
    mount([{min: 1, max: null, label: 'pageSize_0+'}])
    vm.remove(0)
    expect(vm.rows).toHaveLength(1)
    vm.append()
    expect(vm.rows[0].max).toBe(10)
    vm.editEnd(0, '1')
    vm.commitEnd(0)
    vm.split(0)
    expect(vm.rows).toHaveLength(2)
    vm.editEnd(0, String(BUCKET_INT_MAX - 1))
    vm.commitEnd(0)
    expect(vm.canAppend).toBe(false)
    vm.append()
    expect(vm.rows).toHaveLength(2)
  })

  it('supports tagSize from zero and resets local drafts when reloaded', async () => {
    const buckets = [{min: 0, max: null, label: 'tagSize_-1+'}]
    mount(buckets, {start: 0, prefix: 'tagSize_', defaultWidth: 5})
    vm.append()
    expect(vm.rows[0]).toMatchObject({min: 0, max: 4, label: 'tagSize_0-4'})
    vm.editEnd(0, '')
    await wrapper.setProps({buckets: buckets.map(bucket => ({...bucket}))})
    expect(vm.rows).toHaveLength(1)
    expect(vm.valid).toBe(true)
  })

  it('rejects empty lists, discontinuities and finite tails in shared validation', () => {
    expect(validBuckets([], 1, 'pageSize_')).toBe(false)
    expect(validBuckets([{min: 1, max: 10, label: 'pageSize_1-10'}], 1, 'pageSize_')).toBe(false)
    const buckets = initial()
    buckets[1].min = 12
    expect(validBuckets(buckets, 1, 'pageSize_')).toBe(false)
  })
})
