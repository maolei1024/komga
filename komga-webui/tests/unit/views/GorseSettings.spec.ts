import {shallowMount, Wrapper} from '@vue/test-utils'
import Vue from 'vue'
import GorseSettings from '@/views/GorseSettings.vue'

Vue.config.ignoredElements = [/^v-/]

const flushPromises = () => new Promise(resolve => setTimeout(resolve, 0))

const savedSettings = {
  enabled: false,
  apiUrl: 'http://saved-gorse:8088',
  apiKey: 'saved-key',
  feedbackType: 'read',
  positiveFeedbackType: 'like',
  negativeFeedbackType: 'dislike',
  anonymousUserId: '',
  readThreshold: 0.5,
  tagPenaltyExponent: 0.5,
}

describe('GorseSettings', () => {
  let wrapper: Wrapper<Vue> | null = null
  const service = {
    getSettings: jest.fn(async () => ({...savedSettings})),
    updateSettings: jest.fn(async () => undefined),
    testConnection: jest.fn(async () => ({
      ready: true,
      dataStoreConnected: true,
      cacheStoreConnected: true,
      apiAuthenticated: true,
    })),
    syncItems: jest.fn(),
    syncUsers: jest.fn(),
    syncFeedback: jest.fn(),
  }

  beforeEach(() => {
    jest.clearAllMocks()
  })

  afterEach(() => {
    wrapper?.destroy()
    wrapper = null
  })

  async function mount() {
    wrapper = shallowMount(GorseSettings, {
      mocks: {$komgaGorse: service},
      stubs: {
        'v-btn': {
          props: ['disabled', 'loading'],
          template: '<button v-bind="$attrs" :disabled="disabled" @click="$emit(\'click\')"><slot/></button>',
        },
        'v-icon': {template: '<i><slot/></i>'},
      },
    })
    await flushPromises()
    return wrapper
  }

  it('tests the current unsaved form without persisting it', async () => {
    const view = await mount()
    await view.setData({
      form: {...savedSettings, apiUrl: 'http://new-gorse:8089', apiKey: 'new-key'},
      formDirty: true,
    })

    await view.find('[data-testid="gorse-test-connection"]').trigger('click')
    await flushPromises()

    expect(service.testConnection).toHaveBeenCalledWith({
      apiUrl: 'http://new-gorse:8089',
      apiKey: 'new-key',
    })
    expect(service.updateSettings).not.toHaveBeenCalled()
    expect((view.vm as any).formDirty).toBe(true)
    expect((view.vm as any).snackbarText).toBe('Gorse 运行正常')
    expect((view.vm as any).snackbarColor).toBe('success')
  })

  it('shares loading state and ignores duplicate connection tests', async () => {
    let finishTest: ((value: {
      ready: boolean
      dataStoreConnected: boolean
      cacheStoreConnected: boolean
      apiAuthenticated: boolean
    }) => void) | undefined
    service.testConnection.mockImplementationOnce(() => new Promise(resolve => {
      finishTest = resolve
    }))
    const view = await mount()
    const vm = view.vm as any
    const button = view.find('[data-testid="gorse-test-connection"]')

    const firstTest = vm.testConnection()
    const duplicateTest = vm.testConnection()
    await view.vm.$nextTick()

    expect(vm.testingConnection).toBe(true)
    expect(button.props('loading')).toBe(true)
    expect(button.attributes('disabled')).toBe('disabled')
    expect(service.testConnection).toHaveBeenCalledTimes(1)
    await duplicateTest
    finishTest?.({ready: true, dataStoreConnected: true, cacheStoreConnected: true, apiAuthenticated: true})
    await firstTest
    await view.vm.$nextTick()
    expect(vm.testingConnection).toBe(false)
    expect(button.props('loading')).toBe(false)
    expect(button.attributes('disabled')).toBeUndefined()
  })

  it('shows the safe backend reason when the connection test fails', async () => {
    service.testConnection.mockRejectedValueOnce(new Error('Gorse API 密钥验证失败'))
    const view = await mount()

    await view.find('[data-testid="gorse-test-connection"]').trigger('click')
    await flushPromises()

    expect((view.vm as any).snackbarText).toBe('Gorse API 密钥验证失败')
    expect((view.vm as any).snackbarColor).toBe('error')
  })

  it('disables connection testing when the API URL is blank or settings are saving', async () => {
    const view = await mount()
    await view.setData({form: {...savedSettings, apiUrl: '  '}})

    const button = view.find('[data-testid="gorse-test-connection"]')
    expect(button.attributes('disabled')).toBe('disabled')
    await button.trigger('click')
    expect(service.testConnection).not.toHaveBeenCalled()

    await view.setData({form: {...savedSettings}, saving: true})
    expect(button.attributes('disabled')).toBe('disabled')
    await button.trigger('click')
    expect(service.testConnection).not.toHaveBeenCalled()
  })
})
