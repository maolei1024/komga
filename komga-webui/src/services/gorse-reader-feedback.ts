import type KomgaGorseService from '@/services/komga-gorse.service'
import {GorseFeedbackContextDto, GorsePreference} from '@/services/komga-gorse.service'

export class GorseReaderFeedback {
  context: GorseFeedbackContextDto | null = null
  contextLoading = false
  contextFailed = false
  preference: GorsePreference = 'NONE'
  preferenceReady = false
  preferenceLoading = false
  preferenceFailed = false
  saving = false
  saveError = ''
  retryTarget: GorsePreference | null = null
  pending: GorsePreference | null = null
  expanded = false
  saved = false
  private contextVersion = 0
  private preferenceVersion = 0
  private active = true

  constructor(
    readonly seriesId: string,
    readonly title: string,
    readonly incognito: boolean,
    private readonly service: Pick<KomgaGorseService, 'getFeedbackContext' | 'getSeriesPreference' | 'setSeriesPreference'>,
    private readonly saveProgress: () => Promise<void>,
  ) {}

  async load(): Promise<void> {
    await Promise.all([this.loadContext(), this.incognito ? Promise.resolve() : this.loadPreference()])
  }

  dispose() {
    this.active = false
    this.contextVersion++
    this.preferenceVersion++
  }

  toggleDetails() {
    this.expanded = !this.expanded
  }

  async loadContext(): Promise<void> {
    if (!this.active || this.contextLoading) return
    const version = ++this.contextVersion
    this.contextLoading = true
    this.contextFailed = false
    try {
      const context = await this.service.getFeedbackContext(this.seriesId)
      if (version !== this.contextVersion) return
      this.context = context
      this.contextFailed = context.featuresStatus !== 'AVAILABLE'
    } catch (error) {
      if (version === this.contextVersion) this.contextFailed = true
    } finally {
      if (version === this.contextVersion) this.contextLoading = false
    }
  }

  async loadPreference(): Promise<void> {
    if (!this.active || this.incognito || this.preferenceLoading || this.saving) return
    const version = ++this.preferenceVersion
    this.preferenceLoading = true
    this.preferenceReady = false
    this.preferenceFailed = false
    try {
      const result = await this.service.getSeriesPreference(this.seriesId)
      if (version !== this.preferenceVersion) return
      this.preference = result.preference
      this.preferenceReady = true
    } catch (error) {
      if (version === this.preferenceVersion) this.preferenceFailed = true
    } finally {
      if (version === this.preferenceVersion) this.preferenceLoading = false
    }
  }

  async toggle(selected: 'LIKE' | 'DISLIKE'): Promise<void> {
    await this.submit(this.preference === selected ? 'NONE' : selected)
  }

  async retry(): Promise<void> {
    if (this.retryTarget !== null) await this.submit(this.retryTarget)
  }

  private async submit(target: GorsePreference): Promise<void> {
    if (!this.active || this.incognito || !this.preferenceReady || this.saving) return
    const version = ++this.preferenceVersion
    this.saving = true
    this.pending = target
    this.saveError = ''
    this.saved = false
    let progressSaved = false
    try {
      await this.saveProgress()
      if (!this.active || version !== this.preferenceVersion) return
      progressSaved = true
      const result = await this.service.setSeriesPreference(this.seriesId, target)
      if (version !== this.preferenceVersion) return
      this.preference = result.preference
      this.retryTarget = null
      this.saved = true
    } catch (error) {
      if (version !== this.preferenceVersion) return
      this.retryTarget = target
      this.saveError = progressSaved ? 'feedback_failed' : 'progress_failed'
    } finally {
      if (version === this.preferenceVersion) {
        this.saving = false
        this.pending = null
      }
    }
  }
}
