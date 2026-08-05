<template>
  <div>
    <v-skeleton-loader v-if="loading" type="list-item-avatar-three-line@4"/>
    <div v-else-if="clusters.length === 0" class="empty-state" role="status">
      <v-icon size="42" color="contrast-light-2">mdi-check-circle-outline</v-icon>
      <h2>{{ $t('dedup.emptyTitle') }}</h2>
      <p>{{ $t('dedup.emptyBody') }}</p>
    </div>
    <v-list v-else two-line class="cluster-list pa-0">
      <DedupClusterListItem v-for="cluster in clusters" :key="cluster.id" :cluster="cluster" @open="$emit('open', $event)"/>
    </v-list>
  </div>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupClusterSummaryDto} from '@/types/komga-dedup'
import DedupClusterListItem from './DedupClusterListItem.vue'

export default Vue.extend({
  name: 'DedupClusterList',
  components: {DedupClusterListItem},
  props: {
    clusters: {type: Array as () => DedupClusterSummaryDto[], required: true},
    loading: {type: Boolean, default: false},
  },
})
</script>

<style scoped>
.cluster-list { overflow: hidden; border-radius: 10px; }
.empty-state { display: flex; min-height: 260px; flex-direction: column; align-items: center; justify-content: center; padding: 32px 20px; text-align: center; }
.empty-state h2 { margin: 14px 0 6px; font-size: 1.125rem; text-wrap: balance; }
.empty-state p { max-width: 56ch; margin: 0; color: var(--v-contrast-light-2-base); text-wrap: pretty; }
</style>
