<template>
  <section class="cluster-list" :aria-busy="loading">
    <v-skeleton-loader v-if="loading" type="list-item-avatar-three-line@5"/>
    <div v-else-if="clusters.length === 0" class="empty-state">
      <v-icon size="42" color="grey">mdi-vector-link</v-icon>
      <h2>{{ $t('dedup.emptyTitle') }}</h2>
      <p>{{ $t('dedup.emptyBody') }}</p>
    </div>
    <DedupClusterListItem v-for="cluster in clusters" v-else :key="cluster.id" :cluster="cluster" @open="$emit('open', $event)"/>
  </section>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupClusterSummaryDto} from '@/types/komga-dedup'
import DedupClusterListItem from './DedupClusterListItem.vue'
export default Vue.extend({name: 'DedupClusterList', components: {DedupClusterListItem}, props: {
  clusters: {type: Array as () => DedupClusterSummaryDto[], required: true}, loading: {type: Boolean, default: false},
}})
</script>

<style scoped>
.cluster-list { overflow: hidden; background: var(--v-base-base); border-radius: 8px; }
.empty-state { min-height: 300px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 32px; text-align: center; }
.empty-state h2 { margin: 0; font-size: 1.125rem; }
.empty-state p { max-width: 56ch; margin: 0; color: var(--v-contrast-light-2-base); }
</style>
