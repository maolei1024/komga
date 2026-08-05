<template>
  <div class="member-row" :class="{'marked-delete': markedForDeletion}">
    <DedupLazyImage :src="member.thumbnailUrl" :alt="member.title || member.bookId" :width="58" :height="84"/>
    <div class="member-copy">
      <strong>{{ member.title || member.bookId }}</strong>
      <span class="member-path" :title="member.path || ''">{{ member.path || '—' }}</span>
      <span class="member-meta">{{ pageLabel }} · {{ formatDedupBytes(member.fileSize) }}</span>
    </div>
    <div class="member-actions">
      <v-btn small text :disabled="comparisonBase" @click="$emit('compare', member.bookId)">
        <v-icon left small>mdi-compare-horizontal</v-icon>{{ comparisonBase ? $t('dedup.comparisonBase') : $t('dedup.comparePages') }}
      </v-btn>
      <v-btn small :outlined="!markedForDeletion" :color="markedForDeletion ? 'error' : undefined" @click="$emit('toggle', member.bookId)">
        <v-icon left small>{{ markedForDeletion ? 'mdi-undo-variant' : 'mdi-delete-outline' }}</v-icon>
        {{ markedForDeletion ? $t('dedup.cancelDelete') : $t('dedup.markDelete') }}
      </v-btn>
    </div>
  </div>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupClusterMemberDto} from '@/types/komga-dedup'
import {formatDedupBytes} from '@/functions/dedup'
import DedupLazyImage from './DedupLazyImage.vue'

export default Vue.extend({
  name: 'DedupClusterMember',
  components: {DedupLazyImage},
  props: {
    member: {type: Object as () => DedupClusterMemberDto, required: true},
    markedForDeletion: {type: Boolean, default: false},
    comparisonBase: {type: Boolean, default: false},
  },
  computed: {
    pageLabel(): string { return this.member.pageCount == null ? this.$t('dedup.pageCountUnknown').toString() : this.$tc('dedup.pageCount', this.member.pageCount, {count: this.member.pageCount}) },
  },
  methods: {formatDedupBytes},
})
</script>

<style scoped>
.member-row { display: grid; grid-template-columns: 58px minmax(0, 1fr) auto; gap: 14px; align-items: center; padding: 12px 14px; border-bottom: 1px solid var(--v-contrast-1-base); transition: background-color 180ms ease-out; }
.member-row:last-child { border-bottom: 0; }
.member-row.marked-delete { background: color-mix(in srgb, var(--v-error-base) 9%, var(--v-base-base)); }
.member-copy { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.member-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.member-path { overflow: hidden; color: var(--v-contrast-light-2-base); font-size: .8125rem; text-overflow: ellipsis; white-space: nowrap; }
.member-meta { color: var(--v-contrast-light-2-base); font-size: .78rem; }
.member-actions { display: flex; align-items: center; gap: 6px; }
@media (max-width: 760px) {
  .member-row { grid-template-columns: 48px minmax(0, 1fr); padding: 10px; }
  .member-actions { grid-column: 1 / -1; justify-content: flex-end; }
}
@media (prefers-reduced-motion: reduce) { .member-row { transition: none; } }
</style>
