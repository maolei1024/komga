<template>
  <v-list-item class="cluster-row" @click="$emit('open', cluster.id)">
    <div class="cover-stack" aria-hidden="true">
      <DedupLazyImage v-for="cover in cluster.coverMembers.slice(0, 3)" :key="cover.bookId" :src="cover.thumbnailUrl" :alt="''" :width="46" :height="68"/>
    </div>
    <v-list-item-content>
      <v-list-item-title class="cluster-title">{{ cluster.title || $t('dedup.untitledCluster') }}</v-list-item-title>
      <v-list-item-subtitle class="cluster-meta">
        <span>{{ $tc('dedup.memberCount', cluster.memberCount, {count: cluster.memberCount}) }}</span>
        <span>{{ formatDate(cluster.lastModified) }}</span>
      </v-list-item-subtitle>
      <v-alert v-if="cluster.lastAttemptError" dense text type="error" class="attempt-error mt-2 mb-0">{{ cluster.lastAttemptError }}</v-alert>
    </v-list-item-content>
    <v-list-item-action class="row-actions">
      <v-chip v-if="cluster.hasSuggestion" small label color="primary" outlined>{{ $t('dedup.suggestionAvailable') }}</v-chip>
      <v-icon>mdi-chevron-right</v-icon>
    </v-list-item-action>
  </v-list-item>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupClusterSummaryDto} from '@/types/komga-dedup'
import DedupLazyImage from './DedupLazyImage.vue'

export default Vue.extend({
  name: 'DedupClusterListItem',
  components: {DedupLazyImage},
  props: {cluster: {type: Object as () => DedupClusterSummaryDto, required: true}},
  methods: {formatDate(value: string): string { return new Date(value).toLocaleString() }},
})
</script>

<style scoped>
.cluster-row { min-height: 102px; border-bottom: 1px solid var(--v-contrast-1-base); }
.cluster-row:last-child { border-bottom: 0; }
.cover-stack { width: 104px; display: flex; align-items: center; padding-right: 14px; }
.cover-stack > * { overflow: hidden; flex: 0 0 46px; border-radius: 4px; }
.cover-stack > * + * { margin-left: -18px; box-shadow: -2px 0 0 var(--v-base-base); }
.cluster-title { font-weight: 500; }
.cluster-meta { display: flex; flex-wrap: wrap; gap: 6px 16px; margin-top: 5px; }
.attempt-error { max-width: 72ch; font-size: .8125rem; }
.row-actions { flex-direction: row; align-items: center; gap: 10px; }
@media (max-width: 600px) {
  .cluster-row { padding: 10px 8px; }
  .cover-stack { width: 72px; }
  .cover-stack > * { flex-basis: 38px; }
  .row-actions .v-chip { display: none; }
}
</style>
