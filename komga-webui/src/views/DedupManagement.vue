<template>
  <v-container fluid class="dedup-page pa-4 pa-md-6">
    <header class="page-header">
      <div>
        <h1>{{ $t('dedup.title') }}</h1>
        <p>{{ $t('dedup.subtitle') }}</p>
      </div>
      <div class="header-actions">
        <v-btn outlined :loading="scanning" :disabled="filterLocked" @click="requestScan"><v-icon left>mdi-radar</v-icon>{{ $t('dedup.scanNow') }}</v-btn>
        <v-btn color="primary" :loading="bulkVerifying" :disabled="filterLocked || bulkRequests.length === 0" @click="verifyCurrentPage">
          <v-icon left>mdi-flask-outline</v-icon>{{ $t('dedup.verifyCurrentPage', {count: bulkRequests.length}) }}
        </v-btn>
      </div>
    </header>

    <section class="status-strip" aria-label="Dedup status">
      <div><strong>{{ status.clusters.UNPROCESSED || 0 }}</strong><span>{{ $t('dedup.status.UNPROCESSED') }}</span></div>
      <div><strong>{{ status.clusters.PROCESSING || 0 }}</strong><span>{{ $t('dedup.status.PROCESSING') }}</span></div>
      <div><strong>{{ status.clusters.NEEDS_ATTENTION || 0 }}</strong><span>{{ $t('dedup.status.NEEDS_ATTENTION') }}</span></div>
      <div><strong>{{ pendingWork }}</strong><span>{{ $t('dedup.pendingWork') }}</span></div>
    </section>

    <v-expansion-panels flat class="settings-panel mb-5">
      <v-expansion-panel>
        <v-expansion-panel-header><span><v-icon left>mdi-tune-variant</v-icon>{{ $t('dedup.scanSettings') }}</span></v-expansion-panel-header>
        <v-expansion-panel-content>
          <v-alert v-if="settings.libraries.length === 0" type="info" text>{{ $t('dedup.noLibraries') }}</v-alert>
          <div v-for="library in settings.libraries" :key="library.libraryId" class="settings-row">
            <div class="settings-library"><strong>{{ libraryName(library.libraryId) }}</strong><v-switch v-model="library.enabled" dense hide-details :label="$t('dedup.enabled')" @change="settingsDirty = true"/></div>
            <v-select v-model="library.scanInterval" :items="intervalItems" dense outlined hide-details :label="$t('dedup.scanInterval')" :disabled="!library.enabled" @change="settingsDirty = true"/>
            <v-text-field v-model.number="library.coverCandidateDistance" type="number" min="0" max="256" dense outlined hide-details :label="$t('dedup.coverDistance')" :disabled="!library.enabled" @input="settingsDirty = true"/>
            <v-text-field v-model.number="library.coverTopK" type="number" min="1" dense outlined hide-details :label="$t('dedup.topK')" :disabled="!library.enabled" @input="settingsDirty = true"/>
          </div>
          <div class="settings-actions"><v-btn text :disabled="!settingsDirty" @click="loadSettings">{{ $t('common.cancel') }}</v-btn><v-btn color="primary" :loading="savingSettings" :disabled="!settingsDirty" @click="saveSettings">{{ $t('common.save_changes') }}</v-btn></div>
        </v-expansion-panel-content>
      </v-expansion-panel>
    </v-expansion-panels>

    <section class="filter-bar">
      <v-select v-model="filters.status" :items="statusItems" dense outlined hide-details :label="$t('dedup.statusFilter')" :disabled="filterLocked"/>
      <v-select v-model="filters.libraryId" :items="libraryItems" clearable dense outlined hide-details :label="$t('dedup.libraryFilter')" :disabled="filterLocked"/>
      <v-select v-model="filters.evidence" :items="evidenceItems" clearable dense outlined hide-details :label="$t('dedup.evidenceFilter')" :disabled="filterLocked"/>
      <div class="filter-spacer"/>
      <span class="result-count">{{ $tc('dedup.clusterCount', totalClusters, {count: totalClusters}) }}</span>
      <PageSizeSelect v-model="clusterPageSize" :items="[20, 50, 100]" :disabled="filterLocked"/>
    </section>

    <div class="pagination-row top-pagination">
      <v-pagination v-if="pageCount > 1" v-model="clusterPage" :length="pageCount" :total-visible="paginationVisible" :disabled="filterLocked"/>
    </div>

    <DedupClusterList :clusters="clusters" :loading="loadingClusters" @open="openCluster"/>

    <div class="pagination-row">
      <v-pagination v-if="pageCount > 1" v-model="clusterPage" :length="pageCount" :total-visible="paginationVisible" :disabled="filterLocked"/>
    </div>

    <DedupClusterDialog v-model="dialogOpen" :cluster-id="selectedClusterId" @updated="refreshAfterAction" @notify="notify($event, 'success')"/>

    <v-snackbar v-model="snackbar.show" :color="snackbar.color" :timeout="4500">
      {{ snackbar.text }}
      <template v-slot:action="{attrs}"><v-btn text v-bind="attrs" @click="snackbar.show = false">{{ $t('common.close') }}</v-btn></template>
    </v-snackbar>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import PageSizeSelect from '@/components/PageSizeSelect.vue'
import DedupClusterList from '@/components/dedup/DedupClusterList.vue'
import DedupClusterDialog from '@/components/dedup/DedupClusterDialog.vue'
import {currentPageVerificationRequests} from '@/functions/dedup'
import {
  DedupClusterStatus, DedupClusterSummaryDto, DedupClusterVerificationRequestDto, DedupEvidenceMaturity,
  DedupSettingsDto, DedupStatusDto,
} from '@/types/komga-dedup'

const EMPTY_STATUS: DedupStatusDto = {work: {}, clusters: {} as any, resolutions: {} as any, gorseSync: {}, enabledLibraries: 0, pausedLibraries: 0}

export default Vue.extend({
  name: 'DedupManagement',
  components: {PageSizeSelect, DedupClusterList, DedupClusterDialog},
  data: () => ({
    status: {...EMPTY_STATUS} as DedupStatusDto,
    settings: {libraries: []} as DedupSettingsDto,
    clusters: [] as DedupClusterSummaryDto[],
    totalClusters: 0,
    clusterPage: 1,
    filters: {status: 'UNPROCESSED' as DedupClusterStatus, libraryId: undefined as string | undefined, evidence: undefined as DedupEvidenceMaturity | undefined},
    loadingClusters: true,
    loadingStatus: true,
    scanning: false,
    bulkVerifying: false,
    savingSettings: false,
    settingsDirty: false,
    dialogOpen: false,
    selectedClusterId: '',
    snackbar: {show: false, text: '', color: 'success'},
  }),
  computed: {
    clusterPageSize: {
      get(): number { const value = this.$store.state.persistedState.dedupClusterPageSize; return [20, 50, 100].includes(value) ? value : 20 },
      set(value: number) { this.$store.commit('setDedupClusterPageSize', value); this.clusterPage = 1; this.loadClusters() },
    },
    filterLocked(): boolean { return this.bulkVerifying },
    pendingWork(): number { return (this.status.work.WAITING || 0) + (this.status.work.PENDING || 0) + (this.status.work.RUNNING || 0) },
    pageCount(): number { return Math.max(1, Math.ceil(this.totalClusters / this.clusterPageSize)) },
    paginationVisible(): number { return this.$vuetify.breakpoint.smAndDown ? 5 : this.$vuetify.breakpoint.mdOnly ? 8 : 12 },
    bulkRequests(): DedupClusterVerificationRequestDto[] { return currentPageVerificationRequests(this.clusters) },
    statusItems(): Array<{text: string; value: DedupClusterStatus}> { return ['UNPROCESSED', 'PROCESSING', 'PROCESSED', 'NEEDS_ATTENTION'].map(value => ({text: this.$t(`dedup.status.${value}`).toString(), value: value as DedupClusterStatus})) },
    evidenceItems(): Array<{text: string; value: DedupEvidenceMaturity}> { return ['COVER_ONLY', 'PARTIAL', 'COMPLETE'].map(value => ({text: this.$t(`dedup.maturity.${value}`).toString(), value: value as DedupEvidenceMaturity})) },
    libraryItems(): Array<{text: string; value: string}> { return this.settings.libraries.map(x => ({text: this.libraryName(x.libraryId), value: x.libraryId})) },
    intervalItems(): Array<{text: string; value: string}> { return ['HOURLY', 'EVERY_6H', 'EVERY_12H', 'DAILY', 'WEEKLY'].map(value => ({text: this.$t(`dedup.interval.${value}`).toString(), value})) },
  },
  watch: {
    'filters.status'() { this.filtersChanged() }, 'filters.libraryId'() { this.filtersChanged() }, 'filters.evidence'() { this.filtersChanged() },
    clusterPage() { this.loadClusters() },
  },
  async mounted() { await Promise.all([this.loadSettings(), this.loadStatus(), this.loadClusters()]) },
  methods: {
    async loadSettings() { try { this.settings = await this.$komgaDedup.getSettings(); this.settingsDirty = false } catch (error) { this.notifyError(error) } },
    async loadStatus() { this.loadingStatus = true; try { this.status = await this.$komgaDedup.getStatus() } catch (error) { this.notifyError(error) } finally { this.loadingStatus = false } },
    async loadClusters() {
      this.loadingClusters = true
      try {
        const response = await this.$komgaDedup.getClusters({page: this.clusterPage - 1, size: this.clusterPageSize, status: this.filters.status,
          library_id: this.filters.libraryId, evidence: this.filters.evidence})
        this.clusters = response.content; this.totalClusters = response.totalElements
        const maxPage = Math.max(1, response.totalPages)
        if (this.clusterPage > maxPage) { this.clusterPage = maxPage; return }
      } catch (error) { this.notifyError(error) } finally { this.loadingClusters = false }
    },
    filtersChanged() { if (this.filterLocked) return; if (this.clusterPage !== 1) this.clusterPage = 1; else this.loadClusters() },
    async requestScan() { this.scanning = true; try { const result = await this.$komgaDedup.requestScan(); this.notify(this.$t('dedup.scanQueued', {count: result.requestedLibraries}).toString(), 'success'); await this.loadStatus() } catch (error) { this.notifyError(error) } finally { this.scanning = false } },
    async verifyCurrentPage() {
      const frozen = [...this.bulkRequests]
      const books = this.clusters.reduce((sum, x) => sum + x.memberCount, 0)
      const pairs = this.clusters.reduce((sum, x) => sum + x.totalPairs, 0)
      if (!window.confirm(this.$t('dedup.confirmBulkVerify', {clusters: frozen.length, books, pairs}).toString())) return
      this.bulkVerifying = true
      try {
        const result = await this.$komgaDedup.verifyClusters(frozen)
        this.notify(this.$t('dedup.bulkQueuedResult', {clusters: result.queuedClusters, queued: result.queuedPairs, skipped: result.skippedPairs, stale: result.staleClusters}).toString(), result.failedClusters ? 'warning' : 'success')
        if (result.staleClusters) await this.loadClusters()
        await this.loadStatus()
      } catch (error) { this.notifyError(error) } finally { this.bulkVerifying = false }
    },
    async saveSettings() { this.savingSettings = true; try { this.settings = await this.$komgaDedup.updateSettings(this.settings); this.settingsDirty = false; this.notify(this.$t('dedup.settingsSaved').toString(), 'success'); await this.loadStatus() } catch (error) { this.notifyError(error) } finally { this.savingSettings = false } },
    openCluster(clusterId: string) { this.selectedClusterId = clusterId; this.dialogOpen = true },
    async refreshAfterAction() { await Promise.all([this.loadClusters(), this.loadStatus()]) },
    libraryName(libraryId: string): string { return this.$store.state.komgaLibraries.libraries.find((item: any) => item.id === libraryId)?.name || libraryId },
    notify(text: string, color = 'success') { this.snackbar = {show: true, text, color} },
    notifyError(error: any) { this.notify(error?.response?.data?.message || error?.message || this.$t('dedup.unknownError').toString(), 'error') },
  },
})
</script>

<style scoped>
.dedup-page { max-width: 1520px; }
.page-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; margin-bottom: 20px; }
.page-header h1 { margin: 0; font-size: 1.5rem; line-height: 1.25; text-wrap: balance; }
.page-header p { max-width: 68ch; margin: 6px 0 0; color: var(--v-contrast-light-2-base); }
.header-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.status-strip { display: flex; flex-wrap: wrap; gap: 2px; margin-bottom: 18px; overflow: hidden; border-radius: 8px; background: var(--v-contrast-1-base); }
.status-strip div { min-width: 130px; flex: 1; display: flex; align-items: baseline; gap: 8px; padding: 12px 16px; background: var(--v-base-base); }
.status-strip strong { font-size: 1.125rem; }
.status-strip span { color: var(--v-contrast-light-2-base); font-size: .8125rem; }
.settings-panel { overflow: hidden; border-radius: 8px; }
.settings-row { display: grid; grid-template-columns: minmax(190px, 1.4fr) repeat(3, minmax(140px, 1fr)); gap: 12px; align-items: center; padding: 12px 0; border-bottom: 1px solid var(--v-contrast-1-base); }
.settings-library { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.settings-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 14px; }
.filter-bar { display: flex; align-items: center; gap: 12px; margin-bottom: 10px; }
.filter-bar > .v-input { max-width: 250px; }
.filter-spacer { flex: 1; }
.result-count { white-space: nowrap; color: var(--v-contrast-light-2-base); font-size: .875rem; }
.pagination-row { min-height: 52px; display: flex; align-items: center; justify-content: center; }
.top-pagination { min-height: 44px; }
@media (max-width: 900px) { .settings-row { grid-template-columns: 1fr 1fr; } .filter-bar { align-items: stretch; flex-wrap: wrap; } .filter-bar > .v-input { max-width: none; flex: 1 1 220px; } .filter-spacer { display: none; } }
@media (max-width: 600px) { .page-header { flex-direction: column; } .header-actions { width: 100%; } .header-actions .v-btn { flex: 1; } .settings-row { grid-template-columns: 1fr; } .result-count { order: 2; flex: 1; } }
</style>
