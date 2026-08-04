<template>
  <section>
    <div class="matrix-scroll">
      <table class="relation-matrix">
        <thead><tr><th>{{ $t('dedup.relationMatrix') }}</th><th v-for="member in members" :key="member.bookId" :title="member.title || member.bookId">{{ shortTitle(member) }}</th></tr></thead>
        <tbody>
        <tr v-for="(row, rowIndex) in members" :key="row.bookId">
          <th :title="row.title || row.bookId">{{ shortTitle(row) }}</th>
          <td v-for="(column, columnIndex) in members" :key="column.bookId">
            <span v-if="rowIndex === columnIndex" aria-hidden="true">—</span>
            <button v-else-if="rowIndex < columnIndex" type="button" :class="{'selected-cell': isSelected(row.bookId, column.bookId)}"
                    @click="selectPair(row.bookId, column.bookId)">
              {{ relationFor(row.bookId, column.bookId)?.type || $t('dedup.unverified') }}
            </button>
          </td>
        </tr>
        </tbody>
      </table>
    </div>
    <dl v-if="selectedRelation" class="relation-metrics">
      <template v-for="metric in metrics">
        <div v-if="metric.value != null" :key="metric.key"><dt>{{ $t(`dedup.metric.${metric.key}`) }}</dt><dd>{{ metric.value }}</dd></div>
      </template>
    </dl>
    <p v-else class="hint">{{ $t('dedup.selectPairHint') }}</p>
  </section>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupClusterMemberDto, DedupRelationDto} from '@/types/komga-dedup'
export default Vue.extend({
  name: 'DedupRelationMatrix',
  props: {
    members: {type: Array as () => DedupClusterMemberDto[], required: true},
    relations: {type: Array as () => DedupRelationDto[], required: true},
    selected: {type: Object as () => {leftBookId: string; rightBookId: string} | null, default: null},
  },
  computed: {
    selectedRelation(): DedupRelationDto | null { return this.selected ? this.relationFor(this.selected.leftBookId, this.selected.rightBookId) : null },
    metrics(): Array<{key: string; value: number | null}> {
      const r = this.selectedRelation
      if (!r) return []
      return [
        {key: 'coverDistance', value: r.coverDistance}, {key: 'coverageLeft', value: r.coverageLeft},
        {key: 'coverageRight', value: r.coverageRight}, {key: 'orderConsistency', value: r.orderConsistency},
        {key: 'longestMatchedRun', value: r.longestMatchedRun}, {key: 'unmatchedPrefix', value: r.unmatchedPrefixCount},
        {key: 'unmatchedSuffix', value: r.unmatchedSuffixCount}, {key: 'unmatchedInternal', value: r.unmatchedInternalCount},
      ]
    },
  },
  methods: {
    shortTitle(member: DedupClusterMemberDto): string { const title = member.title || member.bookId; return title.length > 16 ? `${title.slice(0, 15)}…` : title },
    relationFor(left: string, right: string): DedupRelationDto | null { return this.relations.find(x => new Set([x.leftBookId, x.rightBookId]).has(left) && new Set([x.leftBookId, x.rightBookId]).has(right)) || null },
    isSelected(left: string, right: string): boolean { return !!this.selected && new Set([this.selected.leftBookId, this.selected.rightBookId]).has(left) && new Set([this.selected.leftBookId, this.selected.rightBookId]).has(right) },
    selectPair(leftBookId: string, rightBookId: string) { this.$emit('select', {leftBookId, rightBookId}) },
  },
})
</script>

<style scoped>
.matrix-scroll { overflow: auto; max-width: 100%; }
.relation-matrix { min-width: 620px; width: 100%; border-collapse: collapse; font-size: .75rem; }
.relation-matrix th, .relation-matrix td { min-width: 96px; padding: 7px; border-bottom: 1px solid var(--v-contrast-1-base); text-align: center; }
.relation-matrix th:first-child { position: sticky; left: 0; z-index: 1; max-width: 150px; background: var(--v-base-base); text-align: left; }
.relation-matrix button { width: 100%; min-height: 34px; padding: 4px 7px; color: inherit; background: var(--v-contrast-1-base); border: 0; border-radius: 4px; font: inherit; cursor: pointer; }
.relation-matrix button:hover, .relation-matrix button:focus-visible, .relation-matrix button.selected-cell { color: var(--v-primary-base); box-shadow: inset 0 0 0 2px var(--v-primary-base); outline: none; }
.relation-metrics { display: flex; flex-wrap: wrap; gap: 12px 24px; margin: 16px 0 0; }
.relation-metrics div { min-width: 120px; }
.relation-metrics dt { color: var(--v-contrast-light-2-base); font-size: .75rem; }
.relation-metrics dd { margin: 2px 0 0; font-weight: 500; }
.hint { margin: 14px 0 0; color: var(--v-contrast-light-2-base); font-size: .875rem; }
</style>
