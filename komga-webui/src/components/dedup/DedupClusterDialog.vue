<template>
  <v-dialog :value="value" max-width="1180" scrollable persistent @input="onDialogInput">
    <v-card class="cluster-dialog">
      <v-card-title class="dialog-header">
        <div>
          <h1>{{ detail?.summary.title || $t('dedup.dialogTitle') }}</h1>
          <p v-if="detail">{{ $tc('dedup.memberCount', detail.members.length, {count: detail.members.length}) }}</p>
        </div>
        <v-btn icon :aria-label="$t('common.close')" :disabled="busy" @click="close"><v-icon>mdi-close</v-icon></v-btn>
      </v-card-title>

      <v-card-text class="dialog-body">
        <v-skeleton-loader v-if="loading" type="list-item-avatar-three-line@4"/>
        <v-alert v-else-if="loadError" type="error" text>{{ loadError }}</v-alert>
        <template v-else-if="detail">
          <v-alert v-if="detail.summary.lastAttemptError" type="error" text class="mb-4">{{ detail.summary.lastAttemptError }}</v-alert>
          <v-alert v-if="detail.suggestion" type="info" text class="suggestion-note">
            <strong>{{ $t('dedup.suggestionAvailable') }}</strong>
            <span>{{ $t('dedup.suggestionSummary', {keeper: suggestedKeeperTitle, count: detail.suggestion.deleteCount}) }}</span>
          </v-alert>

          <section class="dialog-section">
            <div class="section-heading">
              <div><h2>{{ $t('dedup.booksToReview') }}</h2><p>{{ $t('dedup.selectionHelp') }}</p></div>
              <v-chip small label :color="deleteIds.length ? 'error' : undefined" outlined>
                {{ $t('dedup.selectionCount', {remove: deleteIds.length, keep: keepCount}) }}
              </v-chip>
            </div>
            <div class="member-list">
              <DedupClusterMember
                v-for="member in detail.members"
                :key="member.bookId"
                :member="member"
                :marked-for-deletion="deleteIds.includes(member.bookId)"
                :comparison-base="comparisonBaseId === member.bookId"
                @toggle="toggleDelete"
                @compare="compareWithBase"
              />
            </div>
          </section>

          <section class="dialog-section comparison-section">
            <div class="section-heading comparison-heading">
              <div><h2>{{ $t('dedup.pageComparison') }}</h2><p>{{ $t('dedup.comparisonHelp') }}</p></div>
              <v-select v-model="comparisonBaseId" :items="comparisonBaseItems" dense outlined hide-details :label="$t('dedup.comparisonBase')"/>
            </div>
            <v-alert v-if="!pageComparison && !pagesLoading" text type="info" class="mt-3 mb-0">{{ $t('dedup.chooseCompare') }}</v-alert>
            <v-skeleton-loader v-if="pagesLoading" class="mt-3" type="image" height="180"/>
            <div v-else-if="pageComparison" class="page-comparison">
              <v-chip small label outlined>{{ $t(`dedup.relation.${pageComparison.relationType}`) }}</v-chip>
              <div class="page-columns">
                <div v-for="bookId in [pageComparison.leftBookId, pageComparison.rightBookId]" :key="bookId">
                  <h3>{{ memberTitle(bookId) }}</h3>
                  <div class="page-strip">
                    <figure v-for="page in pageComparison.pages[bookId] || []" :key="`${bookId}-${page.pageNumber}`" :class="{'matched-page': page.matchedPageNumber != null}">
                      <DedupLazyImage :src="page.thumbnailUrl" :alt="$t('dedup.pageAlt', {page: page.pageNumber})" :width="72" :height="104" high-priority root-selector=".page-strip" root-margin="216px"/>
                      <figcaption>{{ page.pageNumber }}<span v-if="page.matchedPageNumber != null"> ↔ {{ page.matchedPageNumber }}</span></figcaption>
                    </figure>
                  </div>
                </div>
              </div>
            </div>
          </section>

          <v-alert v-if="actionError" type="error" text class="mt-5">{{ actionError }}</v-alert>
          <DedupResolutionResult :resolution="resolutionResult"/>
        </template>
      </v-card-text>

      <v-card-actions class="dialog-footer">
        <v-btn text :disabled="busy" @click="close">{{ $t('common.close') }}</v-btn>
        <v-spacer/>
        <v-btn v-if="detail?.retryResolutionId" outlined color="error" :loading="busyAction === 'retry'" :disabled="busy && busyAction !== 'retry'" @click="retryResolution">
          <v-icon left>mdi-reload-alert</v-icon>{{ $t('dedup.retryIncomplete') }}
        </v-btn>
        <v-btn v-if="detail?.suggestion" outlined color="primary" :loading="busyAction === 'suggested'" :disabled="busy" @click="submitSuggested">
          {{ suggestedButtonLabel }}
        </v-btn>
        <v-btn color="primary" :loading="busyAction === 'custom'" :disabled="busy || deleteIds.length >= (detail?.members.length || 0)" @click="submitCustom">
          {{ customButtonLabel }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupClusterDetailDto, DedupConflictDto, DedupPageComparisonDto, DedupResolutionDto} from '@/types/komga-dedup'
import {customActionKey} from '@/functions/dedup'
import DedupClusterMember from './DedupClusterMember.vue'
import DedupLazyImage from './DedupLazyImage.vue'
import DedupResolutionResult from './DedupResolutionResult.vue'

export default Vue.extend({
  name: 'DedupClusterDialog',
  components: {DedupClusterMember, DedupLazyImage, DedupResolutionResult},
  props: {value: {type: Boolean, required: true}, clusterId: {type: String, default: null}},
  data: () => ({
    detail: null as DedupClusterDetailDto | null,
    loading: false,
    loadError: '',
    actionError: '',
    busyAction: '' as '' | 'suggested' | 'custom' | 'retry',
    deleteIds: [] as string[],
    comparisonBaseId: '',
    pageComparison: null as DedupPageComparisonDto | null,
    pagesLoading: false,
    resolutionResult: null as DedupResolutionDto | null,
    loadGeneration: 0,
    abortController: null as AbortController | null,
  }),
  computed: {
    busy(): boolean { return this.busyAction !== '' },
    keepCount(): number { return Math.max(0, (this.detail?.members.length || 0) - this.deleteIds.length) },
    suggestedKeeperId(): string { return this.detail?.suggestion?.members.find(member => member.action === 'KEEP')?.bookId || '' },
    suggestedKeeperTitle(): string { return this.memberTitle(this.suggestedKeeperId) },
    comparisonBaseItems(): Array<{text: string; value: string}> {
      return this.detail?.members.map(member => ({text: member.title || member.bookId, value: member.bookId})) || []
    },
    suggestedButtonLabel(): string {
      if (!this.detail?.suggestion) return ''
      return this.$t('dedup.applySuggestion', {keeper: this.suggestedKeeperTitle, count: this.detail.suggestion.deleteCount}).toString()
    },
    customButtonLabel(): string {
      return this.$t(customActionKey(this.deleteIds.length), {remove: this.deleteIds.length, keep: this.keepCount}).toString()
    },
  },
  watch: {
    value(open: boolean) { if (open) this.load(); else this.cancelLoad() },
    clusterId() { if (this.value) this.load() },
    comparisonBaseId() { this.pageComparison = null },
  },
  beforeDestroy() { this.cancelLoad() },
  methods: {
    async load() {
      if (!this.clusterId) return
      this.cancelLoad()
      const generation = ++this.loadGeneration
      const controller = new AbortController()
      this.abortController = controller
      this.loading = true
      this.loadError = ''; this.actionError = ''; this.pageComparison = null; this.resolutionResult = null
      try {
        const detail = await this.$komgaDedup.getCluster(this.clusterId, controller.signal)
        if (generation !== this.loadGeneration) return
        this.detail = detail
        this.deleteIds = []
        this.comparisonBaseId = detail.suggestion?.members.find(member => member.action === 'KEEP')?.bookId || detail.members[0]?.bookId || ''
      } catch (error) {
        if (error?.code !== 'ERR_CANCELED') this.loadError = this.errorMessage(error)
      } finally {
        if (generation === this.loadGeneration) this.loading = false
      }
    },
    onDialogInput(open: boolean) { if (!open) this.close() },
    close() { if (!this.busy) { this.cancelLoad(); this.$emit('input', false) } },
    cancelLoad() { this.abortController?.abort(); this.abortController = null; this.loadGeneration++ },
    toggleDelete(bookId: string) {
      this.deleteIds = this.deleteIds.includes(bookId) ? this.deleteIds.filter(id => id !== bookId) : [...this.deleteIds, bookId]
    },
    async compareWithBase(bookId: string) {
      if (!this.detail || !this.comparisonBaseId || bookId === this.comparisonBaseId) return
      this.pagesLoading = true; this.pageComparison = null; this.actionError = ''
      try { this.pageComparison = await this.$komgaDedup.getPageComparison(this.detail.summary.id, this.comparisonBaseId, bookId) }
      catch (error) { this.actionError = this.errorMessage(error) }
      finally { this.pagesLoading = false }
    },
    async submitSuggested() {
      if (!this.detail?.suggestion) return
      await this.execute('suggested', () => this.$komgaDedup.createSuggestedResolution(this.detail!.summary.id, this.detail!.summary.revision))
    },
    async submitCustom() {
      if (!this.detail || this.deleteIds.length >= this.detail.members.length) return
      await this.execute('custom', () => this.$komgaDedup.createCustomResolution(this.detail!.summary.id, this.detail!.summary.revision, this.deleteIds))
    },
    async retryResolution() {
      if (!this.detail?.retryResolutionId) return
      await this.execute('retry', () => this.$komgaDedup.retryResolution(this.detail!.retryResolutionId!))
    },
    async execute(action: 'suggested' | 'custom' | 'retry', operation: () => Promise<DedupResolutionDto>) {
      this.busyAction = action; this.actionError = ''
      try {
        this.resolutionResult = await operation()
        const queued = this.resolutionResult.state === 'PROCESSING'
        this.$emit('notify', {
          text: this.$t(queued ? 'dedup.resolutionQueued' : 'dedup.resolutionCompleted').toString(),
          color: queued ? 'info' : 'success',
        })
        this.$emit('resolved', this.resolutionResult.clusterId)
        this.$emit('input', false)
      } catch (error) {
        const conflict = error?.response?.data as DedupConflictDto | undefined
        const resolution = conflict?.resolution || null
        const message = conflict?.message || this.errorMessage(error)
        this.$emit('updated')
        if (conflict?.code === 'CLUSTER_STALE' || conflict?.partial) await this.load()
        this.resolutionResult = resolution
        this.actionError = message
      } finally { this.busyAction = '' }
    },
    memberTitle(bookId: string): string { return this.detail?.members.find(member => member.bookId === bookId)?.title || bookId },
    errorMessage(error: any): string { return error?.response?.data?.message || error?.message || this.$t('dedup.unknownError').toString() },
  },
})
</script>

<style scoped>
.cluster-dialog { height: min(92vh, 940px); }
.dialog-header { display: flex; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--v-contrast-1-base); }
.dialog-header h1 { margin: 0; font-size: 1.25rem; text-wrap: balance; }
.dialog-header p { margin: 4px 0 0; color: var(--v-contrast-light-2-base); font-size: .8125rem; }
.dialog-body { padding: 20px 24px 40px !important; }
.suggestion-note { display: flex; flex-direction: column; gap: 3px; }
.dialog-section { margin-top: 24px; }
.dialog-section:first-child { margin-top: 0; }
.section-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 12px; }
.section-heading h2 { margin: 0; font-size: 1rem; text-wrap: balance; }
.section-heading p { max-width: 68ch; margin: 4px 0 0; color: var(--v-contrast-light-2-base); font-size: .8125rem; text-wrap: pretty; }
.member-list { overflow: hidden; border-radius: 8px; background: var(--v-base-base); }
.comparison-heading .v-input { max-width: 320px; }
.page-comparison { margin-top: 14px; }
.page-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; margin-top: 14px; }
.page-columns h3 { margin: 0 0 8px; overflow: hidden; font-size: .875rem; text-overflow: ellipsis; white-space: nowrap; }
.page-strip { display: flex; gap: 7px; overflow-x: auto; padding-bottom: 8px; }
.page-strip figure { flex: 0 0 72px; margin: 0; background: var(--v-contrast-1-base); }
.page-strip figure.matched-page { box-shadow: inset 0 0 0 2px var(--v-success-base); }
.page-strip figcaption { padding: 3px; text-align: center; font-size: .7rem; }
.dialog-footer { position: sticky; bottom: 0; z-index: 2; flex-wrap: wrap; gap: 8px; padding: 12px 20px; border-top: 1px solid var(--v-contrast-1-base); background: var(--v-base-base); }
@media (max-width: 700px) {
  .cluster-dialog { height: 100vh; }
  .dialog-body { padding: 14px 12px 110px !important; }
  .section-heading, .comparison-heading { flex-direction: column; }
  .comparison-heading .v-input { width: 100%; max-width: none; }
  .page-columns { grid-template-columns: 1fr; }
  .dialog-footer { justify-content: stretch; }
  .dialog-footer .v-btn { flex: 1 1 auto; }
}
@media (prefers-reduced-motion: reduce) { * { transition-duration: .01ms !important; } }
</style>
