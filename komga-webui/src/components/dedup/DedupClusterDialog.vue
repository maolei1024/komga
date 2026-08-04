<template>
  <v-dialog :value="value" :fullscreen="$vuetify.breakpoint.smAndDown" max-width="1400" scrollable :persistent="busy" @input="onDialogInput">
    <v-card class="cluster-dialog">
      <v-card-title class="dialog-header">
        <div>
          <span class="text-h6">{{ $t('dedup.dialogTitle') }}</span>
          <div v-if="detail" class="dialog-subtitle">
            <v-chip x-small label :color="statusColor">{{ $t(`dedup.status.${detail.summary.status}`) }}</v-chip>
            <span>{{ $tc('dedup.memberCount', detail.summary.memberCount, {count: detail.summary.memberCount}) }}</span>
            <span>{{ $t('dedup.verifiedProgress', {verified: detail.summary.verifiedPairs, total: detail.summary.totalPairs}) }}</span>
          </div>
        </div>
        <v-btn icon :aria-label="$t('common.close')" :disabled="busy" @click="close"><v-icon>mdi-close</v-icon></v-btn>
      </v-card-title>

      <v-card-text class="dialog-body">
        <v-skeleton-loader v-if="loading" type="article, list-item-avatar-three-line@3"/>
        <v-alert v-else-if="loadError" type="error" text>{{ loadError }}</v-alert>
        <template v-else-if="detail">
          <v-alert v-if="detail.summary.reopenReason" type="info" dense text>
            {{ reopenLabel(detail.summary.reopenReason) }}
          </v-alert>

          <section class="dialog-section">
            <h2>{{ $t('dedup.membersAndActions') }}</h2>
            <div class="member-list">
              <DedupClusterMember v-for="member in detail.members" :key="member.bookId" :member="member"
                :selection="selections[member.bookId]" :keeper-options="keeperOptionsFor(member.bookId)" :disabled="busy"
                @action="setAction(member.bookId, $event)" @keeper="setKeeper(member.bookId, $event)"/>
            </div>
          </section>

          <section class="dialog-section evidence-section">
            <h2>{{ $t('dedup.evidence') }}</h2>
            <DedupRelationMatrix :members="detail.members" :relations="detail.relations" :selected="selectedPair" @select="selectPair"/>
            <div v-if="pagesLoading" class="pages-loading"><v-progress-linear indeterminate color="primary"/></div>
            <div v-else-if="pageComparison" class="page-columns">
              <div v-for="bookId in [pageComparison.leftBookId, pageComparison.rightBookId]" :key="bookId">
                <h3>{{ memberTitle(bookId) }}</h3>
                <div class="page-strip">
                  <figure v-for="page in pageComparison.pages[bookId]" :key="`${bookId}-${page.pageNumber}`" :class="{'matched-page': page.matchedPageNumber != null}">
                    <v-img :src="bookPageThumbnailUrl(page.bookId, page.pageNumber)" width="72" height="104" contain/>
                    <figcaption>{{ page.pageNumber }}<span v-if="page.matchedPageNumber != null"> ↔ {{ page.matchedPageNumber }}</span></figcaption>
                  </figure>
                </div>
              </div>
            </div>
          </section>

          <section v-if="detail.suggestedPlan" class="dialog-section suggestion-section">
            <div class="section-heading"><h2>{{ $t('dedup.suggestedPlan') }}</h2><v-chip small label :color="detail.eligibility.suggestedPlanEligible ? 'success' : 'grey'">
              {{ $t('dedup.planCounts', {keep: detail.suggestedPlan.keepCount, remove: detail.suggestedPlan.deleteCount}) }}
            </v-chip></div>
            <div class="plan-groups">
              <div><strong>{{ $t('dedup.keep') }}</strong><span>{{ planTitles('KEEP') }}</span></div>
              <div><strong>{{ $t('dedup.delete') }}</strong><span>{{ planTitles('DELETE') }}</span></div>
            </div>
          </section>

          <section class="dialog-section">
            <h2>{{ $t('dedup.eligibility') }}</h2>
            <DedupEligibilityReasons :reasons="[...detail.eligibility.blockers, ...detail.eligibility.warnings]" :members="detail.members"/>
            <div v-if="requiredRiskCodes.length" class="risk-confirmations">
              <v-checkbox v-for="code in requiredRiskCodes" :key="code" v-model="acknowledgedReasonCodes" :value="code" dense hide-details
                          :disabled="busy" :label="riskLabel(code)"/>
            </div>
          </section>

          <v-alert v-if="actionError" type="error" text class="mt-4">{{ actionError }}</v-alert>
          <DedupResolutionResult :resolution="resolutionResult"/>
        </template>
      </v-card-text>

      <v-card-actions class="dialog-footer">
        <v-btn text :disabled="busy || !detail" @click="verify"><v-icon left>mdi-flask-outline</v-icon>{{ $t('dedup.verifyNow') }}</v-btn>
        <v-spacer/>
        <v-btn v-if="retryableResolutionId" outlined color="error" :loading="busyAction === 'retry'" :disabled="busy && busyAction !== 'retry'" @click="retryResolution">
          <v-icon left>mdi-reload-alert</v-icon>{{ $t('dedup.retryIncomplete') }}
        </v-btn>
        <v-btn color="primary" outlined :disabled="busy || !detail || !detail.eligibility.suggestedPlanEligible" :loading="busyAction === 'suggested'" @click="submitSuggested">
          {{ $t('dedup.useSuggestion') }}
        </v-btn>
        <v-btn color="primary" :disabled="busy || !customValid" :loading="busyAction === 'custom'" @click="submitCustom">
          {{ $t('dedup.submitCustom') }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script lang="ts">
import Vue from 'vue'
import {
  DedupClusterDetailDto, DedupConflictDto, DedupCustomResolutionMemberDto, DedupPageComparisonDto,
  DedupRelationDto, DedupResolutionAction, DedupResolutionDto,
} from '@/types/komga-dedup'
import {bookPageThumbnailUrl} from '@/functions/urls'
import DedupClusterMember from './DedupClusterMember.vue'
import DedupRelationMatrix from './DedupRelationMatrix.vue'
import DedupEligibilityReasons from './DedupEligibilityReasons.vue'
import DedupResolutionResult from './DedupResolutionResult.vue'

interface MemberSelection { action: DedupResolutionAction; keeperBookId?: string }

const RISK_TYPES = new Set(['VISUALLY_SIMILAR', 'SAME_EDITION_VARIANT', 'NEAR_CONTAINED_IN', 'PARTIAL_OVERLAP', 'ALT_EDITION', 'EDITION_UNCERTAIN'])

export default Vue.extend({
  name: 'DedupClusterDialog',
  components: {DedupClusterMember, DedupRelationMatrix, DedupEligibilityReasons, DedupResolutionResult},
  props: {value: {type: Boolean, required: true}, clusterId: {type: String, default: null}},
  data: () => ({
    detail: null as DedupClusterDetailDto | null,
    loading: false,
    loadError: '',
    actionError: '',
    busyAction: '' as '' | 'verify' | 'suggested' | 'custom' | 'retry',
    selections: {} as Record<string, MemberSelection>,
    acknowledgedReasonCodes: [] as string[],
    selectedPair: null as {leftBookId: string; rightBookId: string} | null,
    pageComparison: null as DedupPageComparisonDto | null,
    pagesLoading: false,
    resolutionResult: null as DedupResolutionDto | null,
    pollTimer: 0 as number,
  }),
  computed: {
    busy(): boolean { return this.busyAction !== '' },
    statusColor(): string { if (!this.detail) return 'grey'; return ({UNPROCESSED: 'info', PROCESSING: 'warning', PROCESSED: 'success', NEEDS_ATTENTION: 'error'} as Record<string, string>)[this.detail.summary.status] },
    keepIds(): string[] { return Object.keys(this.selections).filter(id => this.selections[id].action === 'KEEP') },
    customMembers(): DedupCustomResolutionMemberDto[] { return Object.entries(this.selections).map(([bookId, selection]) => ({bookId, action: selection.action, keeperBookId: selection.keeperBookId})) },
    requiredRiskCodes(): string[] {
      if (!this.detail) return []
      const relationCodes = this.customMembers.filter(x => x.action === 'DELETE' && x.keeperBookId).map(selection => this.relationFor(selection.bookId, selection.keeperBookId!))
        .filter((relation): relation is DedupRelationDto => !!relation && RISK_TYPES.has(relation.type)).map(this.riskCode)
      const localStateCodes = this.customMembers.filter(x => x.action === 'DELETE').flatMap(selection => {
        const member = this.detail!.members.find(x => x.bookId === selection.bookId)
        return (member?.localStateReasonCodes || []).map(code => `LOCAL_STATE_${code}_${selection.bookId}`)
      })
      return [...relationCodes, ...localStateCodes].filter((code, index, all) => all.indexOf(code) === index)
    },
    customValid(): boolean {
      if (!this.detail || this.keepIds.length === 0 || Object.keys(this.selections).length !== this.detail.members.length) return false
      const membersValid = this.customMembers.every(x => {
        if (x.action === 'KEEP') return !x.keeperBookId
        if (!x.keeperBookId || !this.keepIds.includes(x.keeperBookId)) return false
        const relation = this.relationFor(x.bookId, x.keeperBookId)
        if (!relation || relation.type === 'UNRELATED') return false
        return relation.type !== 'CONTAINED_IN' || (relation.containedBookId === x.bookId && relation.containerBookId === x.keeperBookId)
      })
      return membersValid && this.requiredRiskCodes.every(code => this.acknowledgedReasonCodes.includes(code)) && this.detail.summary.status === 'UNPROCESSED'
    },
    retryableResolutionId(): string | null {
      if (this.resolutionResult && ['NEEDS_ATTENTION', 'PARTIALLY_COMPLETED'].includes(this.resolutionResult.state)) return this.resolutionResult.id
      if (this.detail?.lastResolution && ['NEEDS_ATTENTION', 'PARTIALLY_COMPLETED'].includes(this.detail.lastResolution.state)) return this.detail.lastResolution.id
      return null
    },
  },
  watch: {
    value(open: boolean) { if (open) this.load(); else this.stopPolling() },
    clusterId() { if (this.value) this.load() },
  },
  beforeDestroy() { this.stopPolling() },
  methods: {
    bookPageThumbnailUrl,
    async load() {
      if (!this.clusterId) return
      this.loading = true; this.loadError = ''; this.actionError = ''; this.pageComparison = null; this.selectedPair = null
      try {
        this.detail = await this.$komgaDedup.getCluster(this.clusterId)
        this.selections = Object.fromEntries(this.detail.members.map(member => [member.bookId, {action: 'KEEP'}]))
        this.acknowledgedReasonCodes = []
        if (this.detail.lastResolution && ['PROCESSING', 'NEEDS_ATTENTION', 'PARTIALLY_COMPLETED'].includes(this.detail.lastResolution.state)) {
          this.resolutionResult = await this.$komgaDedup.getResolution(this.detail.lastResolution.id)
          if (this.resolutionResult.state === 'PROCESSING') this.schedulePoll()
        }
      } catch (error) { this.loadError = this.errorMessage(error) } finally { this.loading = false }
    },
    onDialogInput(open: boolean) { if (!open) this.close() },
    close() { if (!this.busy) this.$emit('input', false) },
    setAction(bookId: string, action: DedupResolutionAction) {
      const next = {...this.selections, [bookId]: {action} as MemberSelection}
      if (action === 'DELETE') {
        const keepers = Object.keys(next).filter(id => id !== bookId && next[id].action === 'KEEP')
        if (keepers.length === 1) next[bookId].keeperBookId = keepers[0]
      }
      Object.keys(next).forEach(id => { if (next[id].action === 'DELETE' && next[id].keeperBookId === bookId) next[id] = {action: 'DELETE'} })
      this.selections = next
    },
    setKeeper(bookId: string, keeperBookId: string) { this.selections = {...this.selections, [bookId]: {...this.selections[bookId], keeperBookId}} },
    keeperOptionsFor(bookId: string): Array<{bookId: string; title: string}> { if (!this.detail) return []; return this.detail.members.filter(x => x.bookId !== bookId && this.selections[x.bookId]?.action === 'KEEP').map(x => ({bookId: x.bookId, title: x.title || x.bookId})) },
    relationFor(left: string, right: string): DedupRelationDto | null { return this.detail?.relations.find(x => new Set([x.leftBookId, x.rightBookId]).has(left) && new Set([x.leftBookId, x.rightBookId]).has(right)) || null },
    riskCode(relation: DedupRelationDto): string { return `RISK_${relation.type}_${relation.leftBookId}_${relation.rightBookId}` },
    riskLabel(code: string): string {
      if (code.startsWith('LOCAL_STATE_')) {
        const member = this.detail?.members.find(x => code.endsWith(`_${x.bookId}`))
        const reason = member?.localStateReasonCodes.find(x => code === `LOCAL_STATE_${x}_${member.bookId}`) || code
        const key = `dedup.localState.${reason}`
        return this.$t('dedup.acknowledgeLocalState', {state: this.$te(key) ? this.$t(key) : reason, book: member?.title || member?.bookId || ''}).toString()
      }
      const type = code.split('_').slice(1, -2).join('_'); const key = `dedup.relation.${type}`
      return this.$t('dedup.acknowledgeRisk', {type: this.$te(key) ? this.$t(key) : type}).toString()
    },
    async selectPair(pair: {leftBookId: string; rightBookId: string}) {
      this.selectedPair = pair; this.pagesLoading = true; this.pageComparison = null
      try { this.pageComparison = await this.$komgaDedup.getPageComparison(this.clusterId, pair.leftBookId, pair.rightBookId) }
      catch (error) { this.actionError = this.errorMessage(error) } finally { this.pagesLoading = false }
    },
    async verify() {
      if (!this.detail) return
      this.busyAction = 'verify'; this.actionError = ''
      try { await this.$komgaDedup.verifyCluster(this.detail.summary.id, this.detail.summary.revision); this.$emit('notify', this.$t('dedup.verificationQueued').toString()); this.$emit('updated') }
      catch (error) { this.captureError(error) } finally { this.busyAction = '' }
    },
    async submitSuggested() {
      if (!this.detail?.suggestedPlan || !window.confirm(this.$t('dedup.confirmSuggested', {count: this.detail.suggestedPlan.deleteCount}).toString())) return
      await this.execute('suggested', () => this.$komgaDedup.createSuggestedResolution(this.detail!.summary.id, this.detail!.summary.revision, this.detail!.stateRevision, this.detail!.suggestedPlan!.revision))
    },
    async submitCustom() {
      if (!this.detail || !this.customValid) return
      const deletes = this.customMembers.filter(x => x.action === 'DELETE').length
      const key = deletes === 0 ? 'dedup.confirmKeepAll' : 'dedup.confirmCustom'
      if (!window.confirm(this.$t(key, {count: deletes}).toString())) return
      await this.execute('custom', () => this.$komgaDedup.createCustomResolution(this.detail!.summary.id, {
        expectedRevision: this.detail!.summary.revision, stateRevision: this.detail!.stateRevision,
        members: this.customMembers, acknowledgedReasonCodes: this.acknowledgedReasonCodes,
      }))
    },
    async retryResolution() { if (this.retryableResolutionId) await this.execute('retry', () => this.$komgaDedup.retryResolution(this.retryableResolutionId!)) },
    async execute(action: 'suggested' | 'custom' | 'retry', operation: () => Promise<DedupResolutionDto>) {
      this.busyAction = action; this.actionError = ''
      try { this.resolutionResult = await operation(); this.$emit('updated'); await this.load() }
      catch (error) { this.captureError(error) } finally { this.busyAction = '' }
    },
    captureError(error: any) { const conflict = error?.response?.data as DedupConflictDto | undefined; if (conflict?.resolution) this.resolutionResult = conflict.resolution; this.actionError = conflict?.message || this.errorMessage(error); if (conflict?.code?.includes('STALE')) this.$emit('updated') },
    errorMessage(error: any): string { return error?.response?.data?.message || error?.message || this.$t('dedup.unknownError').toString() },
    memberTitle(bookId: string): string { return this.detail?.members.find(x => x.bookId === bookId)?.title || bookId },
    planTitles(action: DedupResolutionAction): string { if (!this.detail?.suggestedPlan) return ''; return this.detail.suggestedPlan.members.filter(x => x.action === action).map(x => this.memberTitle(x.bookId)).join(' · ') },
    reopenLabel(code: string): string { const key = `dedup.reopen.${code}`; return this.$te(key) ? this.$t(key).toString() : code },
    schedulePoll() { this.stopPolling(); this.pollTimer = window.setTimeout(async () => { if (!this.resolutionResult) return; try { this.resolutionResult = await this.$komgaDedup.getResolution(this.resolutionResult.id); if (this.resolutionResult.state === 'PROCESSING') this.schedulePoll(); else { this.$emit('updated'); await this.load() } } catch (_) { this.schedulePoll() } }, 3000) },
    stopPolling() { if (this.pollTimer) window.clearTimeout(this.pollTimer); this.pollTimer = 0 },
  },
})
</script>

<style scoped>
.cluster-dialog { height: 90vh; }
.dialog-header { display: flex; justify-content: space-between; gap: 16px; border-bottom: 1px solid var(--v-contrast-1-base); }
.dialog-subtitle { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-top: 5px; color: var(--v-contrast-light-2-base); font-size: .8125rem; }
.dialog-body { padding: 20px 24px 40px !important; }
.dialog-section { margin-top: 24px; }
.dialog-section:first-child { margin-top: 0; }
.dialog-section h2 { margin: 0 0 12px; font-size: 1rem; text-wrap: balance; }
.member-list { overflow: hidden; border-radius: 8px; background: var(--v-base-base); }
.evidence-section { padding-top: 4px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.suggestion-section { padding: 16px; background: var(--v-contrast-1-base); border-radius: 8px; }
.plan-groups { display: flex; flex-wrap: wrap; gap: 18px 40px; }
.plan-groups div { display: flex; flex-direction: column; gap: 3px; max-width: 62ch; }
.plan-groups span { color: var(--v-contrast-light-2-base); font-size: .875rem; }
.page-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; margin-top: 18px; }
.page-columns h3 { margin: 0 0 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: .875rem; }
.page-strip { display: flex; gap: 7px; overflow-x: auto; padding-bottom: 8px; }
.page-strip figure { flex: 0 0 72px; margin: 0; background: var(--v-contrast-1-base); }
.page-strip figure.matched-page { box-shadow: inset 0 0 0 2px var(--v-success-base); }
.page-strip figcaption { padding: 3px; text-align: center; font-size: .7rem; }
.pages-loading { margin-top: 16px; }
.risk-confirmations { margin-top: 12px; padding: 10px 14px; background: var(--v-contrast-1-base); border-radius: 8px; }
.dialog-footer { position: sticky; bottom: 0; z-index: 2; flex-wrap: wrap; gap: 8px; padding: 12px 20px; background: var(--v-base-base); border-top: 1px solid var(--v-contrast-1-base); }
@media (max-width: 700px) { .cluster-dialog { height: 100vh; } .dialog-body { padding: 14px 12px 100px !important; } .page-columns { grid-template-columns: 1fr; } .dialog-footer { justify-content: stretch; } .dialog-footer .v-btn { flex: 1 1 auto; } }
@media (prefers-reduced-motion: reduce) { * { transition-duration: .01ms !important; } }
</style>
