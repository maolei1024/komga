<template>
  <div class="gorse-preference" role="group" aria-label="Gorse 偏好">
    <v-tooltip bottom>
      <template v-slot:activator="{ on }">
        <v-btn
          data-testid="gorse-like"
          icon
          v-on="on"
          :aria-label="preference === 'LIKE' ? '取消喜欢' : '喜欢'"
          :aria-pressed="preference === 'LIKE'"
          :loading="loading && pendingPreference === 'LIKE'"
          :disabled="loading"
          @click.stop="togglePreference('LIKE')"
        >
          <v-icon :color="preference === 'LIKE' ? 'red' : 'grey'">
            {{ preference === 'LIKE' ? 'mdi-heart' : 'mdi-heart-outline' }}
          </v-icon>
        </v-btn>
      </template>
      <span>{{ preference === 'LIKE' ? '取消喜欢' : '喜欢' }}</span>
    </v-tooltip>

    <v-tooltip bottom>
      <template v-slot:activator="{ on }">
        <v-btn
          data-testid="gorse-dislike"
          icon
          v-on="on"
          :aria-label="preference === 'DISLIKE' ? '取消不感兴趣' : '不感兴趣'"
          :aria-pressed="preference === 'DISLIKE'"
          :loading="loading && pendingPreference === 'DISLIKE'"
          :disabled="loading"
          @click.stop="togglePreference('DISLIKE')"
        >
          <v-icon :color="preference === 'DISLIKE' ? 'primary' : 'grey'">
            {{ preference === 'DISLIKE' ? 'mdi-thumb-down' : 'mdi-thumb-down-outline' }}
          </v-icon>
        </v-btn>
      </template>
      <span>{{ preference === 'DISLIKE' ? '取消不感兴趣' : '不感兴趣' }}</span>
    </v-tooltip>
    <v-btn v-if="retryPreference !== null" data-testid="gorse-retry" text small :disabled="loading" @click.stop="retry">
      {{ $t('gorse_reading.retry') }}
    </v-btn>
  </div>
</template>

<script lang="ts">
import Vue from 'vue'
import {GorsePreference, GorsePreferenceDto} from '@/services/komga-gorse.service'
import {ERROR, ErrorEvent} from '@/types/events'

export default Vue.extend({
  name: 'GorseLikeButton',
  props: {
    seriesId: {
      type: String,
      default: '',
    },
    bookId: {
      type: String,
      default: '',
    },
  },
  data() {
    return {
      preference: 'NONE' as GorsePreference,
      loading: false,
      pendingPreference: null as GorsePreference | null,
      requestVersion: 0,
      retryPreference: null as GorsePreference | null,
    }
  },
  watch: {
    seriesId: {
      immediate: true,
      handler(val: string) {
        if (val) this.fetchPreference()
      },
    },
    bookId: {
      immediate: true,
      handler(val: string) {
        if (val && !this.seriesId) this.fetchPreference()
      },
    },
  },
  beforeDestroy() {
    this.requestVersion++
  },
  methods: {
    async fetchPreference() {
      const version = ++this.requestVersion
      this.loading = true
      this.pendingPreference = null
      this.retryPreference = null
      this.preference = 'NONE'
      try {
        const result = this.seriesId
          ? await this.$komgaGorse.getSeriesPreference(this.seriesId)
          : await this.$komgaGorse.getBookPreference(this.bookId)
        if (version === this.requestVersion) this.preference = result.preference
      } catch (e) {
        if (version === this.requestVersion) this.notifyError('加载 Gorse 偏好失败')
      } finally {
        if (version === this.requestVersion) this.loading = false
      }
    },
    async togglePreference(selected: Exclude<GorsePreference, 'NONE'>) {
      if (this.loading) return
      const target: GorsePreference = this.preference === selected ? 'NONE' : selected
      await this.savePreference(target, selected)
    },
    async retry() {
      if (this.retryPreference === null || this.loading) return
      await this.savePreference(this.retryPreference, this.retryPreference)
    },
    async savePreference(target: GorsePreference, selected: GorsePreference) {
      const version = ++this.requestVersion
      const seriesId = this.seriesId
      const bookId = this.bookId
      this.loading = true
      this.pendingPreference = selected
      try {
        const result: GorsePreferenceDto = seriesId
          ? await this.$komgaGorse.setSeriesPreference(seriesId, target)
          : await this.$komgaGorse.setBookPreference(bookId, target)
        if (version !== this.requestVersion) return
        this.preference = result.preference
        this.retryPreference = null
      } catch (e) {
        if (version !== this.requestVersion) return
        this.retryPreference = target
        this.notifyError('更新 Gorse 偏好失败')
      } finally {
        if (version === this.requestVersion) {
          this.loading = false
          this.pendingPreference = null
        }
      }
    },
    notifyError(message: string) {
      this.$eventHub.$emit(ERROR, {message} as ErrorEvent)
    },
  },
})
</script>

<style scoped>
.gorse-preference {
  display: inline-flex;
  align-items: center;
}
</style>
