<template>
  <v-tooltip bottom>
    <template v-slot:activator="{ on }">
      <v-btn icon
             v-on="on"
             @click.stop="toggleLike"
             :loading="loading"
             :disabled="loading"
      >
        <v-icon :color="liked ? 'red' : 'grey'">
          {{ liked ? 'mdi-heart' : 'mdi-heart-outline' }}
        </v-icon>
      </v-btn>
    </template>
    <span>{{ liked ? '取消喜欢' : '喜欢' }}</span>
  </v-tooltip>
</template>

<script lang="ts">
import Vue from 'vue'

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
      liked: false,
      loading: false,
      resolvedSeriesId: '',
    }
  },
  watch: {
    seriesId: {
      immediate: true,
      handler(val: string) {
        if (val) {
          this.resolvedSeriesId = val
          this.fetchLikeStatus()
        }
      },
    },
    bookId: {
      immediate: true,
      handler(val: string) {
        if (val && !this.seriesId) {
          this.fetchLikeStatusByBook()
        }
      },
    },
  },
  methods: {
    async fetchLikeStatus() {
      if (!this.resolvedSeriesId) return
      this.loading = true
      try {
        const result = await this.$komgaGorse.getLikeStatus(this.resolvedSeriesId)
        this.liked = result.liked
      } catch (e) {
        console.error('Failed to fetch like status', e)
      } finally {
        this.loading = false
      }
    },
    async fetchLikeStatusByBook() {
      if (!this.bookId) return
      this.loading = true
      try {
        const result = await this.$komgaGorse.getLikeStatusByBook(this.bookId)
        this.liked = result.liked
        this.resolvedSeriesId = result.seriesId
      } catch (e) {
        console.error('Failed to fetch like status by book', e)
      } finally {
        this.loading = false
      }
    },
    async toggleLike() {
      if (!this.resolvedSeriesId) return
      this.loading = true
      try {
        if (this.liked) {
          await this.$komgaGorse.unlikeSeries(this.resolvedSeriesId)
          this.liked = false
        } else {
          await this.$komgaGorse.likeSeries(this.resolvedSeriesId)
          this.liked = true
        }
      } catch (e) {
        console.error('Failed to toggle like', e)
      } finally {
        this.loading = false
      }
    },
  },
})
</script>
