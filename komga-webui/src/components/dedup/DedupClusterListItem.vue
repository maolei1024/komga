<template>
  <button class="cluster-row" type="button" @click="$emit('open', cluster.id)">
    <span class="cover-stack" aria-hidden="true">
      <v-img v-for="(member, index) in cluster.coverMembers" :key="member.bookId"
             :src="bookThumbnailUrl(member.bookId)" :style="coverStyle(index)" class="cover" contain/>
    </span>
    <span class="cluster-main">
      <span class="cluster-heading">
        <strong>{{ title }}</strong>
        <v-chip x-small :color="statusColor" label>{{ $t(`dedup.status.${cluster.status}`) }}</v-chip>
      </span>
      <span class="cluster-meta">
        {{ $tc('dedup.memberCount', cluster.memberCount, {count: cluster.memberCount}) }}
        <span aria-hidden="true">·</span>
        {{ $t('dedup.verifiedProgress', {verified: cluster.verifiedPairs, total: cluster.totalPairs}) }}
      </span>
      <span v-if="cluster.reopenReason" class="reopen-reason">
        <v-icon x-small>mdi-refresh-alert</v-icon>
        {{ reasonLabel(cluster.reopenReason) }}
      </span>
    </span>
    <span class="cluster-plan">
      <v-icon :color="cluster.suggestionPlanAvailable ? 'success' : 'grey'" small>
        {{ cluster.suggestionPlanAvailable ? 'mdi-shield-check' : 'mdi-shield-outline' }}
      </v-icon>
      <span v-if="cluster.suggestionPlanAvailable">
        {{ $t('dedup.planCounts', {keep: cluster.suggestedKeepCount, remove: cluster.suggestedDeleteCount}) }}
      </span>
      <span v-else>{{ $t('dedup.noSafeSuggestion') }}</span>
    </span>
    <v-icon class="row-chevron">mdi-chevron-right</v-icon>
  </button>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupClusterSummaryDto} from '@/types/komga-dedup'
import {bookThumbnailUrl} from '@/functions/urls'

export default Vue.extend({
  name: 'DedupClusterListItem',
  props: {cluster: {type: Object as () => DedupClusterSummaryDto, required: true}},
  computed: {
    title(): string { return this.cluster.coverMembers.map(x => x.title).filter(Boolean).slice(0, 2).join(' / ') || this.$t('dedup.untitledCluster').toString() },
    statusColor(): string { return ({UNPROCESSED: 'info', PROCESSING: 'warning', PROCESSED: 'success', NEEDS_ATTENTION: 'error'} as Record<string, string>)[this.cluster.status] },
  },
  methods: {
    bookThumbnailUrl,
    coverStyle(index: number) { return {left: `${index * 18}px`, zIndex: this.cluster.coverMembers.length - index} },
    reasonLabel(code: string): string { const key = `dedup.reopen.${code}`; return this.$te(key) ? this.$t(key).toString() : code },
  },
})
</script>

<style scoped>
.cluster-row { width: 100%; min-height: 116px; display: flex; align-items: center; gap: 20px; padding: 16px 20px; color: inherit; text-align: left; background: transparent; border: 0; border-bottom: 1px solid var(--v-contrast-1-base); cursor: pointer; transition: background-color 180ms ease-out; }
.cluster-row:hover, .cluster-row:focus-visible { background: var(--v-contrast-1-base); outline: none; }
.cluster-row:focus-visible { box-shadow: inset 0 0 0 2px var(--v-primary-base); }
.cover-stack { position: relative; flex: 0 0 104px; height: 84px; }
.cover { position: absolute; top: 0; width: 58px; height: 84px; background: var(--v-contrast-1-base); box-shadow: 0 2px 6px rgba(0,0,0,.22); }
.cluster-main { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 7px; }
.cluster-heading { display: flex; align-items: center; gap: 10px; }
.cluster-heading strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 1rem; }
.cluster-meta, .cluster-plan, .reopen-reason { color: var(--v-contrast-light-2-base); font-size: .875rem; }
.reopen-reason { display: flex; align-items: center; gap: 4px; color: var(--v-warning-base); }
.cluster-plan { flex: 0 0 220px; display: flex; gap: 8px; align-items: center; }
.row-chevron { flex: 0 0 auto; }
@media (max-width: 760px) {
  .cluster-row { min-height: 104px; gap: 12px; padding: 12px; }
  .cover-stack { flex-basis: 76px; height: 72px; }
  .cover { width: 48px; height: 72px; }
  .cluster-plan { display: none; }
  .cluster-heading { align-items: flex-start; flex-direction: column-reverse; gap: 5px; }
}
</style>
