<template>
  <div v-if="displayReasons.length" class="reasons">
    <v-alert v-for="reason in displayReasons" :key="reason.key" dense text :type="reason.severity === 'BLOCKER' ? 'error' : 'warning'" class="mb-2">
      <div class="reason-title">{{ reasonLabel(reason.code) }}</div>
      <div v-if="reason.memberIds.length" class="reason-members">{{ memberNames(reason.memberIds) }}</div>
      <div v-if="reason.actual != null || reason.threshold != null" class="reason-detail">
        <span v-if="reason.actual != null">{{ $t('dedup.actual') }}: {{ reason.actual }}</span>
        <span v-if="reason.threshold != null">{{ $t('dedup.threshold') }}: {{ reason.threshold }}</span>
      </div>
    </v-alert>
  </div>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupEligibilityDisplayReason, mergeEligibilityReasons} from '@/functions/dedup'
import {DedupClusterMemberDto, DedupEligibilityReasonDto} from '@/types/komga-dedup'
export default Vue.extend({
  name: 'DedupEligibilityReasons',
  props: {reasons: {type: Array as () => DedupEligibilityReasonDto[], required: true}, members: {type: Array as () => DedupClusterMemberDto[], required: true}},
  computed: {displayReasons(): DedupEligibilityDisplayReason[] { return mergeEligibilityReasons(this.reasons) }},
  methods: {
    reasonLabel(code: string): string { const key = `dedup.reason.${code}`; return this.$te(key) ? this.$t(key).toString() : code },
    memberNames(ids: string[]): string { return ids.map(id => this.members.find(x => x.bookId === id)?.title || id).join(' · ') },
  },
})
</script>

<style scoped>
.reason-title { font-weight: 600; }
.reason-members, .reason-detail { margin-top: 3px; font-size: .8125rem; }
.reason-detail { display: flex; gap: 16px; }
</style>
