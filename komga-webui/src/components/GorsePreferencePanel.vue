<template>
  <v-card outlined class="mt-6">
    <v-card-title>我的推荐偏好</v-card-title>
    <v-card-text>
      <p>拖动蓝点选择中心，拖动橙点调整范围。Tag 数只统计内容标签。</p>
      <v-alert v-if="error" type="error" dense>{{ error }}</v-alert>
      <v-alert v-if="status" :type="status.status === 'FAILED' ? 'error' : 'info'" dense>
        {{ statusText }}<span v-if="status.lastError">：{{ status.lastError }}</span>
      </v-alert>
      <v-progress-linear v-if="busy && status && status.total" :value="100 * status.processed / status.total" class="mb-4"/>
      <v-row>
        <v-col v-for="direction in directions" :key="direction.key" cols="12" md="6">
          <div class="text-h6 mb-3">{{ direction.label }}</div>
          <v-text-field v-model.number="profile[direction.key].count" label="样本数量" type="number" min="0" max="5000"
                        outlined dense :disabled="busy" @change="preview"/>
          <preference-gaussian-curve v-model="profile[direction.key].pages" label="页数" :min="1" :max="240" @change="preview"/>
          <preference-gaussian-curve v-model="profile[direction.key].tags" label="内容 Tag 数" :min="0" :max="60" @change="preview"/>
        </v-col>
      </v-row>
      <p>正向与负向合计 {{ total }} / 5,000 条。低概率区域不会自动变成“不喜欢”。</p>
      <div class="d-flex flex-wrap" style="gap: 12px">
        <v-btn outlined :loading="previewing" :disabled="busy" @click="preview">更新预览</v-btn>
        <v-btn outlined :disabled="busy || previewing" @click="saveDraft">保存草稿</v-btn>
        <v-btn color="primary" :loading="saving" :disabled="busy || !valid || previewing" @click="apply">清理旧偏好并应用</v-btn>
        <v-btn outlined :disabled="busy" @click="withdraw">撤回全部人工偏好</v-btn>
        <v-btn v-if="status && status.previousProfile" text :disabled="busy" @click="restore">载入上一方案</v-btn>
      </div>
      <p class="mt-4">只有点击应用才会投递；每次先清理旧人工样本及反馈，真实收藏和不喜欢记录保留。</p>
      <p>投递完成后等待 Gorse 自然更新推荐。同数量替换可能被服务跳过重训，投递成功不表示推荐已经更新。</p>
      <template v-if="result">
        <v-alert v-if="result.conflicts.length" type="warning" dense>正负偏好在 {{ result.conflicts.length }} 个桶组合中冲突，请调整曲线后应用。</v-alert>
        <div class="text-subtitle-1 mt-4">分桶热力图</div>
        <p>蓝色为喜欢，橙色为不喜欢；每格显示正／负样本数，括号内为现有作品数。</p>
        <v-simple-table dense>
          <thead><tr><th>页数 ＼ Tag 数</th><th v-for="tag in tagBuckets" :key="tag">{{ shortLabel(tag) }}</th></tr></thead>
          <tbody>
            <tr v-for="page in pageBuckets" :key="page">
              <th>{{ shortLabel(page) }}</th>
              <td v-for="cell in row(page)" :key="cell.tagBucket" :style="{backgroundColor: color(cell)}">
                {{ cell.positive }}／{{ cell.negative }}<br><small>（{{ cell.available }}）</small>
              </td>
            </tr>
          </tbody>
        </v-simple-table>
      </template>
    </v-card-text>
  </v-card>
</template>

<script lang="ts">
import Vue from 'vue'
import PreferenceGaussianCurve from '@/components/PreferenceGaussianCurve.vue'
import {emptyProfile, PreferenceCell, PreferencePreview, PreferenceProfileStatus} from '@/services/gorse-preference-profile'

const endpoint = '/api/v1/gorse/preference-profile'
export default Vue.extend({
  name: 'GorsePreferencePanel',
  components: {PreferenceGaussianCurve},
  data: () => ({
    profile: emptyProfile(),
    status: null as PreferenceProfileStatus | null,
    result: null as PreferencePreview | null,
    error: '',
    saving: false,
    previewing: false,
    previewSequence: 0,
    poll: null as ReturnType<typeof setTimeout> | null,
    directions: [{key: 'positive' as const, label: '希望多看'}, {key: 'negative' as const, label: '希望少看'}],
  }),
  computed: {
    total(): number { return Number(this.profile.positive.count) + Number(this.profile.negative.count) },
    busy(): boolean { return !!this.status && ['PENDING', 'CLEANING', 'INSERTING'].includes(this.status.status) },
    valid(): boolean { return this.total >= 0 && this.total <= 5000 && !!this.result && !this.result.conflicts.length },
    pageBuckets(): string[] { return [...new Set(this.result?.cells.map(cell => cell.pageBucket) || [])] },
    tagBuckets(): string[] { return [...new Set(this.result?.cells.map(cell => cell.tagBucket) || [])] },
    statusText(): string {
      if (!this.status) return ''
      return ({DRAFT: '草稿，尚未投递', PENDING: '等待处理', CLEANING: '正在清理旧人工偏好', INSERTING: '正在投递新人工偏好',
        DELIVERED: `已投递 ${this.status.delivered} 条，等待 Gorse 自然更新`, FAILED: '处理失败，可重试或撤回'} as Record<string, string>)[this.status.status] || this.status.status
    },
  },
  async mounted() {
    try { this.status = (await this.$http.get<PreferenceProfileStatus>(endpoint)).data; this.profile = JSON.parse(JSON.stringify(this.status.profile)); await this.preview(); this.schedulePoll() }
    catch (error) { this.error = String(error) }
  },
  beforeDestroy() { if (this.poll) clearTimeout(this.poll) },
  methods: {
    async preview() {
      const sequence = ++this.previewSequence
      this.previewing = true; this.error = ''; this.result = null
      try {
        const result = (await this.$http.post<PreferencePreview>(endpoint + '/preview', this.profile)).data
        if (sequence === this.previewSequence) this.result = result
      }
      catch (error) { if (sequence === this.previewSequence) this.error = error.response?.data?.message || String(error) }
      finally { if (sequence === this.previewSequence) this.previewing = false }
    },
    async saveDraft() {
      this.error = ''
      try { this.status = (await this.$http.put<PreferenceProfileStatus>(endpoint, this.profile)).data }
      catch (error) { this.error = error.response?.data?.message || String(error) }
    },
    async apply() {
      this.saving = true; this.error = ''
      try {
        await this.preview()
        if (!this.valid) return
        await this.$http.put(endpoint, this.profile)
        this.status = (await this.$http.post<PreferenceProfileStatus>(endpoint + '/apply')).data
        this.schedulePoll()
      } catch (error) { this.error = error.response?.data?.message || String(error) }
      finally { this.saving = false }
    },
    async withdraw() {
      this.error = ''
      try { this.status = (await this.$http.delete<PreferenceProfileStatus>(endpoint + '/samples')).data; this.profile = emptyProfile(); await this.preview(); this.schedulePoll() }
      catch (error) { this.error = error.response?.data?.message || String(error) }
    },
    schedulePoll() {
      if (this.poll) clearTimeout(this.poll)
      if (this.busy) this.poll = setTimeout(async () => {
        try { this.status = (await this.$http.get<PreferenceProfileStatus>(endpoint + '/status')).data; this.schedulePoll() }
        catch (error) { this.error = String(error) }
      }, 2000)
    },
    restore() { if (this.status?.previousProfile) { this.profile = JSON.parse(JSON.stringify(this.status.previousProfile)); this.preview() } },
    shortLabel(label: string): string { return label.replace(/^(pagesize|tagsize)_/, '') },
    row(page: string): PreferenceCell[] { return this.result?.cells.filter(cell => cell.pageBucket === page) || [] },
    color(cell: PreferenceCell): string {
      if (cell.positive && cell.negative) return 'rgba(244,67,54,.35)'
      const strength = Math.min(.7, .08 + Math.log1p(cell.positive + cell.negative) / 10)
      return cell.positive ? `rgba(33,150,243,${strength})` : cell.negative ? `rgba(255,152,0,${strength})` : ''
    },
  },
})
</script>
