<template>
  <span ref="container" class="lazy-image" :style="containerStyle">
    <img
      v-if="active && !failed"
      class="lazy-content"
      :class="contain ? 'lazy-content--contain' : 'lazy-content--cover'"
      :src="src"
      :alt="alt"
      decoding="async"
      @load="finish"
      @error="fail"
    >
    <span v-else class="lazy-placeholder" aria-hidden="true"/>
  </span>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupImageJob, dedupImageQueue} from '@/functions/dedup-image-queue'

export default Vue.extend({
  name: 'DedupLazyImage',
  props: {
    src: {type: String, required: true},
    alt: {type: String, default: ''},
    width: {type: Number, required: true},
    height: {type: Number, required: true},
    contain: {type: Boolean, default: true},
    highPriority: {type: Boolean, default: false},
    rootSelector: {type: String, default: ''},
    rootMargin: {type: String, default: '300px'},
  },
  data: () => ({active: false, failed: false, observer: null as IntersectionObserver | null, job: null as DedupImageJob | null, releaseSlot: null as (() => void) | null}),
  computed: {
    containerStyle(): Record<string, string> { return {width: `${this.width}px`, height: `${this.height}px`} },
  },
  mounted() {
    if (!('IntersectionObserver' in window)) { this.enqueue(); return }
    const element = this.$refs.container as Element
    const root = this.rootSelector ? element.closest(this.rootSelector) : null
    this.observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) this.enqueue()
    }, {root, rootMargin: this.rootMargin})
    this.observer.observe(element)
  },
  beforeDestroy() {
    this.observer?.disconnect()
    if (this.job) this.job.cancelled = true
    this.finish()
  },
  methods: {
    enqueue() {
      if (this.active || this.job) return
      this.observer?.disconnect()
      this.job = {
        priority: this.highPriority ? 'high' : 'normal',
        cancelled: false,
        start: release => {
          this.releaseSlot = release
          this.active = true
        },
      }
      dedupImageQueue.enqueue(this.job)
    },
    finish() {
      this.releaseSlot?.()
      this.releaseSlot = null
    },
    fail() {
      this.failed = true
      this.finish()
    },
  },
})
</script>

<style scoped>
.lazy-image { display: inline-block; overflow: hidden; background: var(--v-contrast-1-base); }
.lazy-content { display: block; width: 100%; height: 100%; }
.lazy-content--contain { object-fit: contain; }
.lazy-content--cover { object-fit: cover; }
.lazy-placeholder { display: block; width: 100%; height: 100%; background: var(--v-contrast-1-base); }
</style>
