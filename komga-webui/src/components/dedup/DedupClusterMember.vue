<template>
  <article class="member-row" :class="{'delete-selected': selection.action === 'DELETE'}">
    <DedupLazyImage :src="bookThumbnailUrl(member.bookId)" class="member-cover" :width="74" :height="104"/>
    <div class="member-info">
      <strong>{{ member.title || member.bookId }}</strong>
      <span class="path" :title="member.path || ''">{{ member.path || '—' }}</span>
      <span>{{ formatBytes(member.fileSize) }} · {{ member.pageCount == null ? $t('dedup.pageCountUnknown') : $tc('dedup.pageCount', member.pageCount, {count: member.pageCount}) }}</span>
      <div class="state-chips">
        <v-chip v-for="code in processing.localStateReasonCodes" :key="code" x-small label>{{ reasonLabel(code) }}</v-chip>
        <v-chip v-if="processing.archiveHashState !== 'READY'" x-small color="warning" label>{{ $t('dedup.deepVerificationRequired') }}</v-chip>
        <v-chip v-if="!member.inMvpScope" x-small color="warning" label>{{ $t('dedup.outOfScope') }}</v-chip>
      </div>
    </div>
    <div class="member-action">
      <v-btn-toggle :value="selection.action" mandatory dense :disabled="disabled" @change="setAction">
        <v-btn value="KEEP" small>{{ $t('dedup.keep') }}</v-btn>
        <v-btn value="DELETE" small color="error">{{ $t('dedup.delete') }}</v-btn>
      </v-btn-toggle>
      <v-select v-if="selection.action === 'DELETE'" :value="selection.keeperBookId" :items="keeperOptions"
                item-text="title" item-value="bookId" dense outlined hide-details :disabled="disabled"
                :label="$t('dedup.keeperForDelete')" @change="$emit('keeper', $event)"/>
    </div>
  </article>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupClusterMemberDto, DedupClusterMemberProcessingDto, DedupResolutionAction} from '@/types/komga-dedup'
import {bookThumbnailUrl} from '@/functions/urls'
import {formatBytes} from '@/functions/dedup'
import DedupLazyImage from './DedupLazyImage.vue'
export default Vue.extend({
  name: 'DedupClusterMember',
  components: {DedupLazyImage},
  props: {
    member: {type: Object as () => DedupClusterMemberDto, required: true},
    processing: {type: Object as () => DedupClusterMemberProcessingDto, default: () => ({bookId: '', archiveHashState: 'MISSING', localStateReasonCodes: [], localState: {}})},
    selection: {type: Object as () => {action: DedupResolutionAction; keeperBookId?: string}, required: true},
    keeperOptions: {type: Array as () => Array<{bookId: string; title: string}>, default: () => []},
    disabled: {type: Boolean, default: false},
  },
  methods: {
    bookThumbnailUrl, formatBytes,
    setAction(action: DedupResolutionAction) { if (action) this.$emit('action', action) },
    reasonLabel(code: string): string { const key = `dedup.localState.${code}`; return this.$te(key) ? this.$t(key).toString() : code },
  },
})
</script>

<style scoped>
.member-row { display: flex; gap: 14px; align-items: center; min-height: 128px; padding: 12px; border-bottom: 1px solid var(--v-contrast-1-base); transition: background-color 180ms ease-out; }
.member-row.delete-selected { background: rgba(255, 3, 53, .07); }
.member-cover { flex: 0 0 74px; background: var(--v-contrast-1-base); }
.member-info { min-width: 0; flex: 1; display: flex; flex-direction: column; gap: 4px; font-size: .8125rem; color: var(--v-contrast-light-2-base); }
.member-info strong { color: var(--v-base-contrast); font-size: .9375rem; }
.path { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.state-chips { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 3px; }
.member-action { flex: 0 0 250px; display: flex; flex-direction: column; gap: 10px; }
@media (max-width: 700px) { .member-row { align-items: flex-start; flex-wrap: wrap; } .member-action { flex-basis: 100%; padding-left: 88px; } }
</style>
