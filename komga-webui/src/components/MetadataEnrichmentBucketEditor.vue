<template>
  <div class="bucket-editor">
    <p class="text-caption mb-3">起始值自动衔接上一档；按 Enter 或离开输入框应用结束值，只调整下一档。最后一档始终无上限。</p>
    <div v-for="(row, index) in rows" :key="row.id" class="bucket-row">
      <div class="bucket-range">
        <v-text-field :value="row.min" label="起始" readonly dense hide-details="auto"/>
        <v-text-field
          v-if="index < rows.length - 1"
          :ref="`end-${row.id}`"
          :value="row.end"
          type="number"
          :min="row.min"
          :max="endLimit(index)"
          step="1"
          label="结束"
          dense
          hide-details="auto"
          :error-messages="endError(index)"
          @input="editEnd(index, $event)"
          @blur="commitEnd(index)"
          @keydown.enter.prevent="commitEnd(index)"
        />
        <v-text-field v-else value="无上限" label="结束" readonly dense hide-details="auto"/>
      </div>
      <div class="bucket-label">
        <v-textarea
          :value="row.label"
          rows="1"
          auto-grow
          label="标签"
          dense
          hide-details="auto"
          :error-messages="errors[index]"
          @input="editLabel(index, $event)"
        />
        <div class="text-caption mt-1">
          <span v-if="row.auto">自动命名</span>
          <v-btn v-else text x-small @click="restoreLabel(index)">恢复自动命名</v-btn>
        </div>
      </div>
      <div class="bucket-actions">
        <span v-if="index < rows.length - 1" :title="row.max === row.min ? '单值区间无法继续拆分' : '按中点拆分为两档'">
          <v-btn small text :disabled="!valid || row.max === row.min" @click="split(index)">拆分</v-btn>
        </span>
        <span :title="rows.length === 1 ? '至少保留一档' : deleteHint(index)">
          <v-btn small text :disabled="rows.length === 1 || !valid" :aria-label="`删除第 ${index + 1} 档，${deleteHint(index)}`" @click="remove(index)">删除</v-btn>
        </span>
      </div>
    </div>
    <v-btn outlined small color="primary" class="mt-3" :disabled="!valid || !canAppend" @click="append">
      <v-icon small left>mdi-plus</v-icon>新增一档
    </v-btn>
    <p v-if="!canAppend && rows.length" class="text-caption mt-2">已达到整数范围上限，无法继续新增。</p>
  </div>
</template>

<script lang="ts">
import Vue, {PropType} from 'vue'
import {MetadataEnrichmentBucket} from '@/services/komga-metadata-enrichment.service'
import {BUCKET_INT_MAX, bucketErrors, bucketLabel} from '@/functions/metadata-enrichment-buckets'

type BucketRow = MetadataEnrichmentBucket & {id: number; end: string; auto: boolean}

export default Vue.extend({
  name: 'MetadataEnrichmentBucketEditor',
  props: {
    buckets: {type: Array as PropType<MetadataEnrichmentBucket[]>, required: true},
    start: {type: Number, required: true},
    prefix: {type: String, required: true},
    defaultWidth: {type: Number, required: true},
  },
  data: () => ({
    rows: [] as BucketRow[],
    nextId: 0,
    emittedBuckets: null as MetadataEnrichmentBucket[] | null,
  }),
  computed: {
    errors(): string[] {
      return bucketErrors(this.rows, this.start, this.prefix)
    },
    valid(): boolean {
      return this.rows.length > 0 && this.errors.every(error => !error) && this.rows.every((row, index) => !this.endError(index) && (row.max === null || Number(row.end) === row.max))
    },
    canAppend(): boolean {
      return this.rows.length > 0 && this.rows[this.rows.length - 1].min < BUCKET_INT_MAX
    },
  },
  watch: {
    buckets: {
      immediate: true,
      handler(value: MetadataEnrichmentBucket[]) {
        if (value === this.emittedBuckets) return
        this.rows = value.map(bucket => this.makeRow(bucket))
        this.$emit('validity', this.valid)
      },
    },
  },
  methods: {
    makeRow(bucket: MetadataEnrichmentBucket): BucketRow {
      return {...bucket, id: this.nextId++, end: bucket.max === null ? '' : String(bucket.max), auto: bucket.label === bucketLabel(bucket, this.prefix)}
    },
    endLimit(index: number): number {
      const next = this.rows[index + 1]
      return next && next.max !== null ? next.max - 1 : BUCKET_INT_MAX - 1
    },
    endError(index: number): string {
      if (index === this.rows.length - 1) return ''
      const row = this.rows[index]
      const value = Number(row.end)
      const limit = this.endLimit(index)
      return row.end.trim() === '' || !Number.isInteger(value) || value < row.min || value > limit
        ? `请输入 ${row.min} 至 ${limit} 的整数；不能越过下一档`
        : ''
    },
    publish() {
      this.emittedBuckets = this.rows.map(({min, max, label}) => ({min, max, label}))
      this.$emit('update:buckets', this.emittedBuckets)
      this.$emit('validity', this.valid)
      this.$emit('changed')
    },
    rename(row: BucketRow) {
      if (row.auto) row.label = bucketLabel(row, this.prefix)
    },
    editEnd(index: number, value: string | number) {
      this.rows[index].end = String(value ?? '')
      // An uncommitted draft must never save the old boundary on Enter or programmatic save.
      this.$emit('validity', false)
      this.$emit('changed')
    },
    commitEnd(index: number) {
      if (index >= this.rows.length - 1) return
      if (this.endError(index)) {
        this.$emit('validity', false)
        return
      }
      const row = this.rows[index]
      row.max = Number(row.end)
      row.end = String(row.max)
      this.rows[index + 1].min = row.max + 1
      this.rename(row)
      this.rename(this.rows[index + 1])
      this.publish()
    },
    editLabel(index: number, value: string) {
      this.rows[index].label = value
      this.rows[index].auto = false
      this.publish()
    },
    restoreLabel(index: number) {
      this.rows[index].auto = true
      this.rename(this.rows[index])
      this.publish()
    },
    split(index: number) {
      if (!this.valid) return
      const row = this.rows[index]
      if (row.max === null || row.max === row.min) return
      const oldMax = row.max
      row.max = row.min + Math.floor((row.max - row.min) / 2)
      row.end = String(row.max)
      this.rename(row)
      const bucket = {min: row.max + 1, max: oldMax, label: ''}
      bucket.label = bucketLabel(bucket, this.prefix)
      this.rows.splice(index + 1, 0, this.makeRow(bucket))
      this.publish()
      this.focusEnd(row.id)
    },
    append() {
      if (!this.valid || !this.canAppend) return
      const row = this.rows[this.rows.length - 1]
      const previous = this.rows[this.rows.length - 2]
      const width = previous && previous.max !== null ? previous.max - previous.min + 1 : this.defaultWidth
      row.max = Math.min(row.min + width - 1, BUCKET_INT_MAX - 1)
      row.end = String(row.max)
      this.rename(row)
      const bucket = {min: row.max + 1, max: null, label: ''}
      bucket.label = bucketLabel(bucket, this.prefix)
      this.rows.push(this.makeRow(bucket))
      this.publish()
      this.focusEnd(row.id)
    },
    deleteHint(index: number): string {
      return index === this.rows.length - 1 ? '上一档扩展为无上限' : '范围并入下一档'
    },
    remove(index: number) {
      if (!this.valid || this.rows.length === 1) return
      if (index === this.rows.length - 1) {
        const previous = this.rows[index - 1]
        previous.max = null
        previous.end = ''
        this.rename(previous)
      } else {
        this.rows[index + 1].min = this.rows[index].min
        this.rename(this.rows[index + 1])
      }
      this.rows.splice(index, 1)
      this.publish()
    },
    focusEnd(id: number) {
      this.$nextTick(() => {
        const refs = this.$refs[`end-${id}`] as Vue[] | undefined
        refs?.[0]?.$el?.querySelector('input')?.focus()
      })
    },
  },
})
</script>

<style scoped>
.bucket-row {
  display: grid;
  grid-template-columns: minmax(140px, 2fr) minmax(150px, 3fr) auto;
  align-items: start;
  gap: 12px 16px;
  padding: 16px 0;
  border-bottom: 1px solid rgba(128, 128, 128, .3);
}
.bucket-range {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  font-variant-numeric: tabular-nums;
}
.bucket-label { min-width: 0; }
.bucket-actions { display: flex; justify-content: flex-end; align-self: center; }
.bucket-editor ::v-deep .v-messages__message { line-height: 1.5; }
@media (max-width: 600px) {
  .bucket-row { grid-template-columns: minmax(0, 1fr); gap: 24px; }
}
</style>
