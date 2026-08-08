<template>
  <v-container fluid class="dedup-page pa-4 pa-md-6">
    <header class="page-header">
      <h1>{{ $t('dedup.title') }}</h1>
      <p>{{ $t('dedup.subtitle') }}</p>
    </header>

    <v-tabs v-model="topTab" class="top-tabs" show-arrows>
      <v-tab>{{ $t('dedup.reviewTab') }}</v-tab>
      <v-tab>{{ $t('dedup.settingsTab') }}</v-tab>
    </v-tabs>

    <v-tabs-items v-model="topTab" class="tab-content">
      <v-tab-item>
        <section class="review-toolbar">
          <v-btn-toggle v-model="reviewState" mandatory dense color="primary" class="state-toggle">
            <v-btn value="pending">{{ $t('dedup.pending') }}</v-btn>
            <v-btn value="processed">{{ $t('dedup.processed') }}</v-btn>
          </v-btn-toggle>
          <v-spacer/>
          <v-select v-if="reviewState === 'pending'" v-model="libraryId" :items="libraryItems" clearable dense outlined hide-details :label="$t('dedup.libraryFilter')"/>
          <span class="result-count">{{ reviewCountLabel }}</span>
          <PageSizeSelect v-model="pageSize" :items="[20, 50, 100]"/>
        </section>

        <template v-if="reviewState === 'pending'">
          <DedupClusterList :clusters="clusters" :loading="loadingClusters" @open="openCluster"/>
        </template>
        <template v-else>
          <v-skeleton-loader v-if="loadingHistory" type="list-item-three-line@4"/>
          <div v-else-if="resolutions.length === 0" class="history-empty">
            <v-icon size="42" color="contrast-light-2">mdi-history</v-icon>
            <h2>{{ $t('dedup.noHistoryTitle') }}</h2>
            <p>{{ $t('dedup.noHistoryBody') }}</p>
          </div>
          <v-expansion-panels v-else flat accordion class="history-list">
            <v-expansion-panel v-for="resolution in resolutions" :key="resolution.id">
              <v-expansion-panel-header>
                <div class="history-header">
                  <div><strong>{{ formatDate(resolution.completed || resolution.created) }}</strong><span>{{ resolution.actorId }}</span></div>
                  <div class="history-tags">
                    <v-chip small label outlined>{{ $t(`dedup.mode.${resolution.mode}`) }}</v-chip>
                    <span>{{ $t('dedup.historyCounts', resolutionCounts(resolution)) }}</span>
                  </div>
                </div>
              </v-expansion-panel-header>
              <v-expansion-panel-content>
                <v-simple-table dense>
                  <thead><tr><th>{{ $t('dedup.book') }}</th><th>{{ $t('dedup.action') }}</th><th>{{ $t('dedup.path') }}</th><th>{{ $t('dedup.result') }}</th></tr></thead>
                  <tbody>
                    <tr v-for="member in resolution.members" :key="member.bookId">
                      <td>{{ member.title }}</td>
                      <td>{{ $t(`dedup.actionValue.${member.action}`) }}</td>
                      <td class="history-path" :title="member.path">{{ member.path }}</td>
                      <td>{{ member.lastError || member.resultCode || member.state }}</td>
                    </tr>
                  </tbody>
                </v-simple-table>
              </v-expansion-panel-content>
            </v-expansion-panel>
          </v-expansion-panels>
        </template>

        <div class="pagination-row">
          <v-pagination v-if="pageCount > 1" v-model="page" :length="pageCount" :total-visible="paginationVisible"/>
        </div>
      </v-tab-item>

      <v-tab-item>
        <section class="runtime-summary" aria-label="Dedup runtime status">
          <div><strong>{{ status.pendingScanBooks }}</strong><span>{{ $t('dedup.pendingScans') }}</span></div>
          <div><strong>{{ status.automaticVerificationPairs }}</strong><span>{{ $t('dedup.pendingVerifications') }}</span></div>
          <div><strong>{{ status.unresolvedClusters }}</strong><span>{{ $t('dedup.pendingClusters') }}</span></div>
          <div><strong>{{ status.processedResolutions }}</strong><span>{{ $t('dedup.processedTotal') }}</span></div>
        </section>

        <div class="settings-heading">
          <div><h2>{{ $t('dedup.scanSettings') }}</h2><p>{{ $t('dedup.settingsHelp') }}</p></div>
          <v-btn outlined :loading="scanning" :disabled="status.enabledLibraries === 0" @click="requestScan">
            <v-icon left>mdi-radar</v-icon>{{ $t('dedup.scanNow') }}
          </v-btn>
        </div>

        <v-alert v-if="settings.libraries.length === 0" type="info" text>{{ $t('dedup.noLibraries') }}</v-alert>
        <div v-for="library in settings.libraries" :key="library.libraryId" class="library-settings">
          <div class="library-settings-title">
            <strong>{{ library.libraryName }}</strong>
            <v-switch v-model="library.enabled" dense hide-details :label="$t('dedup.enabled')" @change="settingsDirty = true"/>
          </div>
          <div class="settings-grid">
            <v-select v-model="library.scanInterval" :items="intervalItems" dense outlined hide-details :label="$t('dedup.scanInterval')" :disabled="!library.enabled" @change="settingsDirty = true"/>
            <v-text-field v-model.number="library.batchSize" type="number" min="1" max="10000" dense outlined hide-details :label="$t('dedup.batchSize')" :disabled="!library.enabled" @input="settingsDirty = true"/>
            <v-text-field v-model.number="library.coverCandidateDistance" type="number" min="0" max="256" dense outlined hide-details :label="$t('dedup.coverDistance')" :disabled="!library.enabled" @input="settingsDirty = true"/>
            <v-text-field v-model.number="library.coverTopK" type="number" min="1" max="1000" dense outlined hide-details :label="$t('dedup.topK')" :disabled="!library.enabled" @input="settingsDirty = true"/>
          </div>
        </div>
        <div class="settings-actions">
          <v-btn text :disabled="!settingsDirty || savingSettings" @click="loadSettings">{{ $t('common.cancel') }}</v-btn>
          <v-btn color="primary" :loading="savingSettings" :disabled="!settingsDirty" @click="saveSettings">{{ $t('common.save_changes') }}</v-btn>
        </div>

        <section class="run-information">
          <h2>{{ $t('dedup.runInformation') }}</h2>
          <v-simple-table dense>
            <thead><tr><th>{{ $t('dedup.library') }}</th><th>{{ $t('dedup.lastBatch') }}</th><th>{{ $t('dedup.booksProcessed') }}</th><th>{{ $t('dedup.nextBatch') }}</th></tr></thead>
            <tbody>
              <tr v-for="library in status.libraries" :key="library.libraryId">
                <td>{{ library.libraryName }}</td>
                <td>{{ formatOptionalDate(library.lastBatchDate) }}</td>
                <td>{{ library.lastBatchBookCount }}</td>
                <td>{{ formatOptionalDate(library.nextBatchDate) }}</td>
              </tr>
              <tr v-if="status.libraries.length === 0"><td colspan="4">{{ $t('dedup.noEnabledLibraries') }}</td></tr>
            </tbody>
          </v-simple-table>
        </section>
      </v-tab-item>
    </v-tabs-items>

    <DedupClusterDialog v-model="dialogOpen" :cluster-id="selectedClusterId" @resolved="removeResolvedCluster" @updated="refreshAfterAction" @notify="notify($event.text, $event.color)"/>
    <v-snackbar v-model="snackbar.show" :color="snackbar.color" :timeout="4500">
      {{ snackbar.text }}
      <template v-slot:action="{attrs}"><v-btn text v-bind="attrs" @click="snackbar.show = false">{{ $t('common.close') }}</v-btn></template>
    </v-snackbar>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import PageSizeSelect from '@/components/PageSizeSelect.vue'
import DedupClusterDialog from '@/components/dedup/DedupClusterDialog.vue'
import DedupClusterList from '@/components/dedup/DedupClusterList.vue'
import {resolutionCounts, withoutDedupCluster} from '@/functions/dedup'
import {DedupClusterSummaryDto, DedupResolutionDto, DedupSettingsDto, DedupStatusDto} from '@/types/komga-dedup'

const EMPTY_STATUS: DedupStatusDto = {
  pendingScanBooks: 0,
  automaticVerificationPairs: 0,
  unresolvedClusters: 0,
  processedResolutions: 0,
  enabledLibraries: 0,
  libraries: [],
}

export default Vue.extend({
  name: 'DedupManagement',
  components: {PageSizeSelect, DedupClusterDialog, DedupClusterList},
  data: () => ({
    topTab: 0,
    reviewState: 'pending' as 'pending' | 'processed',
    status: {...EMPTY_STATUS} as DedupStatusDto,
    settings: {libraries: []} as DedupSettingsDto,
    clusters: [] as DedupClusterSummaryDto[],
    resolutions: [] as DedupResolutionDto[],
    totalClusters: 0,
    totalResolutions: 0,
    page: 1,
    libraryId: undefined as string | undefined,
    loadingClusters: true,
    loadingHistory: false,
    scanning: false,
    savingSettings: false,
    settingsDirty: false,
    dialogOpen: false,
    selectedClusterId: '',
    snackbar: {show: false, text: '', color: 'success'},
    requestGeneration: 0,
    abortController: null as AbortController | null,
  }),
  computed: {
    pageSize: {
      get(): number { const value = this.$store.state.persistedState.dedupClusterPageSize; return [20, 50, 100].includes(value) ? value : 20 },
      set(value: number) { this.$store.commit('setDedupClusterPageSize', value); this.page = 1; this.loadReview() },
    },
    reviewTotal(): number { return this.reviewState === 'pending' ? this.totalClusters : this.totalResolutions },
    reviewCountLabel(): string {
      return this.reviewState === 'pending'
        ? this.$tc('dedup.clusterCount', this.reviewTotal, {count: this.reviewTotal})
        : this.$tc('dedup.resolutionCount', this.reviewTotal, {count: this.reviewTotal})
    },
    pageCount(): number { return Math.max(1, Math.ceil(this.reviewTotal / this.pageSize)) },
    paginationVisible(): number { return this.$vuetify.breakpoint.smAndDown ? 5 : this.$vuetify.breakpoint.mdOnly ? 8 : 12 },
    libraryItems(): Array<{text: string; value: string}> { return this.settings.libraries.map(value => ({text: value.libraryName, value: value.libraryId})) },
    intervalItems(): Array<{text: string; value: string}> {
      return ['HOURLY', 'EVERY_6H', 'EVERY_12H', 'DAILY', 'WEEKLY'].map(value => ({text: this.$t(`dedup.interval.${value}`).toString(), value}))
    },
  },
  watch: {
    reviewState() { this.page = 1; this.loadReview() },
    libraryId() { this.page = 1; this.loadReview() },
    page() { this.loadReview() },
    topTab(value: number) { if (value === 1) Promise.all([this.loadSettings(), this.loadStatus()]) },
  },
  async mounted() { await Promise.all([this.loadSettings(), this.loadStatus(), this.loadReview()]) },
  beforeDestroy() { this.cancelRequest() },
  methods: {
    resolutionCounts,
    loadReview() { return this.reviewState === 'pending' ? this.loadClusters() : this.loadHistory() },
    async loadSettings() {
      try { this.settings = await this.$komgaDedup.getSettings(); this.settingsDirty = false }
      catch (error) { this.notifyError(error) }
    },
    async loadStatus() {
      try { this.status = await this.$komgaDedup.getStatus() }
      catch (error) { this.notifyError(error) }
    },
    async loadClusters() {
      const generation = ++this.requestGeneration
      this.cancelRequest(false)
      const controller = new AbortController(); this.abortController = controller; this.loadingClusters = true
      try {
        const response = await this.$komgaDedup.getClusters({page: this.page - 1, size: this.pageSize, library_id: this.libraryId}, controller.signal)
        if (generation !== this.requestGeneration) return
        this.clusters = response.content; this.totalClusters = response.totalElements
        if (this.page > Math.max(1, response.totalPages)) this.page = Math.max(1, response.totalPages)
      } catch (error) { if (error?.code !== 'ERR_CANCELED') this.notifyError(error) }
      finally { if (generation === this.requestGeneration) this.loadingClusters = false }
    },
    async loadHistory() {
      const generation = ++this.requestGeneration
      this.cancelRequest(false); this.loadingHistory = true
      try {
        const response = await this.$komgaDedup.getResolutions(this.page - 1, this.pageSize)
        if (generation !== this.requestGeneration) return
        this.resolutions = response.content; this.totalResolutions = response.totalElements
        if (this.page > Math.max(1, response.totalPages)) this.page = Math.max(1, response.totalPages)
      } catch (error) { this.notifyError(error) }
      finally { if (generation === this.requestGeneration) this.loadingHistory = false }
    },
    cancelRequest(invalidate = true) { this.abortController?.abort(); this.abortController = null; if (invalidate) this.requestGeneration++ },
    async requestScan() {
      this.scanning = true
      try {
        const result = await this.$komgaDedup.requestScan()
        this.notify(this.$t('dedup.scanQueued', {count: result.requestedLibraries}).toString(), 'success')
        await this.loadStatus()
      } catch (error) { this.notifyError(error) }
      finally { this.scanning = false }
    },
    async saveSettings() {
      this.savingSettings = true
      try {
        this.settings = await this.$komgaDedup.updateSettings(this.settings)
        this.settingsDirty = false
        this.notify(this.$t('dedup.settingsSaved').toString(), 'success')
        await this.loadStatus()
      } catch (error) { this.notifyError(error) }
      finally { this.savingSettings = false }
    },
    openCluster(clusterId: string) { this.selectedClusterId = clusterId; this.dialogOpen = true },
    removeResolvedCluster(clusterId: string) {
      const remaining = withoutDedupCluster(this.clusters, clusterId)
      if (remaining.length === this.clusters.length) return
      this.clusters = remaining
      this.totalClusters = Math.max(0, this.totalClusters - 1)
      this.status = {...this.status, unresolvedClusters: Math.max(0, this.status.unresolvedClusters - 1)}
    },
    async refreshAfterAction() { await Promise.all([this.loadReview(), this.loadStatus()]) },
    formatDate(value: string): string { return new Date(value).toLocaleString() },
    formatOptionalDate(value?: string | null): string { return value ? this.formatDate(value) : this.$t('dedup.never').toString() },
    notify(text: string, color = 'success') { this.snackbar = {show: true, text, color} },
    notifyError(error: any) { this.notify(error?.response?.data?.message || error?.message || this.$t('dedup.unknownError').toString(), 'error') },
  },
})
</script>

<style scoped>
.dedup-page { max-width: 1480px; }
.page-header { margin-bottom: 18px; }
.page-header h1 { margin: 0; font-size: 1.5rem; line-height: 1.25; text-wrap: balance; }
.page-header p { max-width: 70ch; margin: 6px 0 0; color: var(--v-contrast-light-2-base); text-wrap: pretty; }
.top-tabs { border-bottom: 1px solid var(--v-contrast-1-base); }
.tab-content { padding-top: 20px; background: transparent !important; }
.review-toolbar { display: flex; min-height: 48px; align-items: center; gap: 12px; margin-bottom: 14px; }
.review-toolbar > .v-input { max-width: 260px; }
.result-count { color: var(--v-contrast-light-2-base); font-size: .875rem; white-space: nowrap; }
.pagination-row { min-height: 58px; display: flex; align-items: center; justify-content: center; }
.history-list { overflow: hidden; border-radius: 10px; }
.history-header { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-right: 12px; }
.history-header > div:first-child { display: flex; flex-direction: column; gap: 3px; }
.history-header span { color: var(--v-contrast-light-2-base); font-size: .8125rem; }
.history-tags { display: flex; align-items: center; gap: 12px; }
.history-path { max-width: 42ch; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-empty { display: flex; min-height: 260px; flex-direction: column; align-items: center; justify-content: center; text-align: center; }
.history-empty h2 { margin: 14px 0 6px; font-size: 1.125rem; }
.history-empty p { max-width: 58ch; margin: 0; color: var(--v-contrast-light-2-base); }
.runtime-summary { display: flex; flex-wrap: wrap; gap: 2px; overflow: hidden; margin-bottom: 26px; border-radius: 8px; background: var(--v-contrast-1-base); }
.runtime-summary div { min-width: 150px; flex: 1; display: flex; align-items: baseline; gap: 8px; padding: 13px 16px; background: var(--v-base-base); }
.runtime-summary strong { font-size: 1.125rem; }
.runtime-summary span { color: var(--v-contrast-light-2-base); font-size: .8125rem; }
.settings-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; margin-bottom: 14px; }
.settings-heading h2, .run-information h2 { margin: 0; font-size: 1.125rem; }
.settings-heading p { max-width: 68ch; margin: 4px 0 0; color: var(--v-contrast-light-2-base); }
.library-settings { padding: 16px 0; border-bottom: 1px solid var(--v-contrast-1-base); }
.library-settings-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.settings-grid { display: grid; grid-template-columns: repeat(4, minmax(150px, 1fr)); gap: 12px; }
.settings-actions { display: flex; justify-content: flex-end; gap: 8px; padding: 16px 0 30px; }
.run-information { margin-top: 8px; }
.run-information h2 { margin-bottom: 12px; }
@media (max-width: 900px) { .settings-grid { grid-template-columns: repeat(2, minmax(150px, 1fr)); } }
@media (max-width: 650px) {
  .dedup-page { padding: 14px 10px !important; }
  .review-toolbar { align-items: stretch; flex-wrap: wrap; }
  .review-toolbar .v-spacer { display: none; }
  .review-toolbar > .v-input { max-width: none; flex-basis: 100%; }
  .state-toggle { width: 100%; }
  .state-toggle .v-btn { flex: 1; }
  .settings-heading { flex-direction: column; }
  .settings-grid { grid-template-columns: 1fr; }
  .history-header { align-items: flex-start; flex-direction: column; }
  .history-tags { flex-wrap: wrap; }
}
</style>
