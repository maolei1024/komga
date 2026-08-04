<template>
  <section v-if="resolution" class="resolution-result">
    <header>
      <div><strong>{{ $t('dedup.resolutionResult') }}</strong><span class="resolution-id">{{ resolution.id }}</span></div>
      <v-chip small label :color="stateColor">{{ $t(`dedup.resolutionState.${resolution.state}`) }}</v-chip>
    </header>
    <v-simple-table dense>
      <thead><tr><th>{{ $t('dedup.book') }}</th><th>{{ $t('dedup.action') }}</th><th>{{ $t('dedup.stepState') }}</th><th>{{ $t('dedup.result') }}</th></tr></thead>
      <tbody><tr v-for="member in resolution.members" :key="member.bookId">
        <td>{{ member.title }}</td><td>{{ $t(`dedup.actionValue.${member.action}`) }}</td><td>{{ member.state }}</td>
        <td :class="{'error--text': member.lastError}">{{ member.lastError || member.resultCode || '—' }}</td>
      </tr></tbody>
    </v-simple-table>
    <template v-if="seriesResults.length">
      <h4>{{ $t('dedup.seriesResults') }}</h4>
      <v-simple-table dense>
        <thead><tr><th>{{ $t('dedup.series') }}</th><th>{{ $t('dedup.stepState') }}</th><th>{{ $t('dedup.expectedHidden') }}</th><th>{{ $t('dedup.result') }}</th></tr></thead>
        <tbody><tr v-for="series in seriesResults" :key="series.seriesId">
          <td class="series-id">{{ series.seriesId }}</td>
          <td>{{ $t(`dedup.gorseState.${series.state}`) }}</td>
          <td>{{ series.expectedHidden == null ? '—' : series.expectedHidden ? $t('dedup.hidden') : $t('dedup.visible') }}</td>
          <td :class="{'error--text': series.error}">{{ series.error || '—' }}</td>
        </tr></tbody>
      </v-simple-table>
    </template>
  </section>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupGorseSeriesResultDto, DedupResolutionDto} from '@/types/komga-dedup'
import {resolutionSeriesResults} from '@/functions/dedup'
export default Vue.extend({name: 'DedupResolutionResult', props: {resolution: {type: Object as () => DedupResolutionDto | null, default: null}}, computed: {
  stateColor(): string { if (!this.resolution) return 'grey'; return ({PROCESSED: 'success', PROCESSING: 'warning', NEEDS_ATTENTION: 'error', PARTIALLY_COMPLETED: 'error'} as Record<string, string>)[this.resolution.state] },
  seriesResults(): DedupGorseSeriesResultDto[] { return resolutionSeriesResults(this.resolution?.result) },
}})
</script>

<style scoped>
.resolution-result { margin-top: 16px; }
.resolution-result header { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.resolution-id { display: block; color: var(--v-contrast-light-2-base); font-family: monospace; font-size: .75rem; }
.resolution-result h4 { margin: 18px 0 6px; font-size: .875rem; }
.series-id { font-family: monospace; font-size: .75rem; }
</style>
