import {shallowMount, Wrapper} from '@vue/test-utils'
import Vue from 'vue'
import MetadataEnrichmentSettings from '@/views/MetadataEnrichmentSettings.vue'

Vue.config.ignoredElements = [/^v-/]

const flushPromises = () => new Promise(resolve => setTimeout(resolve, 0))

const settings = {
  aiEnabled: true,
  aiAutoOnNew: true,
  aiBaseUrl: 'https://ai.example/v1',
  aiModel: 'model',
  aiPrompt: 'model-specific prompt',
  apiKeyConfigured: true,
  aiTimeoutSeconds: 60,
  aiMaxRetries: 3,
  dictionaryUpdatePolicy: 'MARK_STALE',
  pageSizeBuckets: [
    {min: 1, max: 10, label: 'pageSize_1-10'},
    {min: 11, max: null, label: 'pageSize_10+'},
  ],
  tagSizeBuckets: [
    {min: 0, max: 5, label: 'tagSize_0-5'},
    {min: 6, max: null, label: 'tagSize_5+'},
  ],
  baseDictionaryEntryCount: 10,
  overrideEntryCount: 1,
  dictionaryFingerprint: 'hash',
}

describe('MetadataEnrichmentSettings', () => {
  let wrapper: Wrapper<Vue> | null = null
  const service = {
    getSettings: jest.fn(async () => JSON.parse(JSON.stringify(settings))),
    updateSettings: jest.fn(async (_settings: unknown) => undefined),
    getStats: jest.fn(async () => []),
    getStates: jest.fn(async () => ({content: [], totalElements: 0})),
    requestRuns: jest.fn(async () => ({accepted: 7})),
    replaceBaseDictionary: jest.fn(),
    getOverrides: jest.fn(async () => []),
    putOverride: jest.fn(async () => ({
      baseDictionaryEntryCount: 10,
      overrideEntryCount: 2,
      dictionaryFingerprint: 'new-hash',
      invalidatedBooks: 3,
    })),
    deleteOverride: jest.fn(),
    getUntranslatedTags: jest.fn(async () => ({content: [], totalElements: 0})),
  }

  beforeEach(() => jest.clearAllMocks())

  afterEach(() => {
    wrapper?.destroy()
    wrapper = null
  })

  async function mount() {
    wrapper = shallowMount(MetadataEnrichmentSettings, {
      mocks: {
        $komgaMetadataEnrichment: service,
        $store: {state: {komgaLibraries: {libraries: []}}},
        $i18n: {locale: 'zh-CN'},
      },
      stubs: ['router-link', 'bucket-editor'],
    })
    await flushPromises()
    return wrapper
  }

  it('never sends a blank API key back when an existing key is configured', async () => {
    const view = await mount()
    await view.setData({newApiKey: '', clearApiKey: false, settingsDirty: true})

    await (view.vm as any).saveSettings()

    expect(service.updateSettings).toHaveBeenCalledTimes(1)
    expect(service.updateSettings.mock.calls[0][0]).not.toHaveProperty('aiApiKey')
    expect(service.updateSettings.mock.calls[0][0]).not.toHaveProperty('clearAiApiKey')
    expect(service.updateSettings.mock.calls[0][0]).toHaveProperty('aiPrompt', 'model-specific prompt')
  })

  it('submits the visible processor status and library scope for a confirmed batch run', async () => {
    const view = await mount()
    await view.setData({
      stateProcessor: 'AI_TITLE',
      stateStatus: 'STALE',
      stateLibraryId: 'library',
      stateTotal: 7,
      bulkDialog: true,
    })

    await (view.vm as any).confirmBulkRun()

    expect(service.requestRuns).toHaveBeenCalledWith({
      processor: 'AI_TITLE',
      status: 'STALE',
      libraryId: 'library',
    })
    expect((view.vm as any).bulkDialog).toBe(false)
  })

  it('supports preserving an untranslated tag verbatim as an override', async () => {
    const view = await mount()
    await view.setData({missingOverrideForm: {t: 'tag', k: 'new tag', v: ''}})
    ;(view.vm as any).keepMissingOriginal()

    await (view.vm as any).saveMissingOverride()

    expect(service.putOverride).toHaveBeenCalledWith({t: 'tag', k: 'new tag', v: 'new tag'})
  })

  it('does not open a bulk confirmation while the filtered count is loading', async () => {
    const view = await mount()
    await view.setData({
      stateProcessor: 'AI_TITLE',
      stateTotal: 7,
      statesLoading: true,
      bulkDialog: false,
    })

    ;(view.vm as any).openBulkDialog()

    expect((view.vm as any).bulkDialog).toBe(false)
  })
  it('blocks invalid arrays and uncommitted editor drafts before calling the API', async () => {
    const view = await mount()
    const vm = view.vm as any
    await view.setData({settingsDirty: true, pageBucketsValid: false})
    await vm.saveSettings()
    expect(service.updateSettings).not.toHaveBeenCalled()
    await view.setData({pageBucketsValid: true})
    vm.settings.tagSizeBuckets[0].label = 'wrong'
    await vm.saveSettings()
    expect(service.updateSettings).not.toHaveBeenCalled()
    vm.settings.tagSizeBuckets = []
    await vm.saveSettings()
    expect(service.updateSettings).not.toHaveBeenCalled()
  })

  it('accepts editor updates, saves only API fields and reloads saved buckets', async () => {
    const view = await mount()
    const vm = view.vm as any
    const buckets = [
      {min: 1, max: 5, label: 'pageSize_1-5'},
      {min: 6, max: 10, label: 'pageSize_6-10'},
      {min: 11, max: null, label: 'pageSize_10+'},
    ]
    const editor = view.findAll('bucket-editor-stub').at(0)
    editor.vm.$emit('update:buckets', buckets)
    editor.vm.$emit('changed')
    editor.vm.$emit('validity', true)
    await view.vm.$nextTick()
    expect(vm.settingsDirty).toBe(true)
    expect(vm.settings.pageSizeBuckets).toEqual(buckets)
    service.getSettings.mockResolvedValueOnce({...settings, pageSizeBuckets: buckets})
    await vm.saveSettings()
    expect(service.updateSettings.mock.calls[0][0]).toHaveProperty('pageSizeBuckets', buckets)
    expect(vm.settings.pageSizeBuckets).toEqual(buckets)
    expect(vm.settingsDirty).toBe(false)
  })

  it('preserves drafts on save failure and discards them on explicit reload', async () => {
    const view = await mount()
    const vm = view.vm as any
    vm.settings.pageSizeBuckets[0].label = 'pageSize_custom'
    await view.setData({settingsDirty: true})
    service.updateSettings.mockRejectedValueOnce(new Error('offline'))
    await vm.saveSettings()
    expect(vm.settings.pageSizeBuckets[0].label).toBe('pageSize_custom')
    expect(vm.settingsDirty).toBe(true)
    expect(vm.settingsSaving).toBe(false)
    await view.setData({pageBucketsValid: false})
    await vm.loadSettings()
    expect(vm.settings.pageSizeBuckets).toEqual(settings.pageSizeBuckets)
    expect(vm.pageBucketsValid).toBe(true)
    expect(vm.settingsDirty).toBe(false)
  })

})
