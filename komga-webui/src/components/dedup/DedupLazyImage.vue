<template>
  <span ref="container" class="lazy-image" :style="containerStyle" aria-hidden="true">
    <v-img v-if="active" :src="src" :contain="contain" width="100%" height="100%" @load="finish" @error="finish"/>
    <span v-else class="lazy-placeholder"/>
  </span>
</template>

<script lang="ts">
import Vue from 'vue'

interface ImageJob { start: (release: () => void) => void; cancelled: boolean }

const MAX_ACTIVE_IMAGES = 4
let activeImages = 0
const imageQueue: ImageJob[] = []

function pumpQueue() {
  while (activeImages < MAX_ACTIVE_IMAGES && imageQueue.length) {
    const job = imageQueue.shift()!
    if (job.cancelled) continue
    activeImages++
    let released = false
    job.start(() => {
      if (released) return
      released = true
      activeImages--
      pumpQueue()
    })
  }
}

export default Vue.extend({
  name: 'DedupLazyImage',
  props: {
    src: {type: String, required: true},
    width: {type: Number, required: true},
    height: {type: Number, required: true},
    contain: {type: Boolean, default: true},
    rootSelector: {type: String, default: ''},
    rootMargin: {type: String, default: '300px'},
  },
  data: () => ({active: false, observer: null as IntersectionObserver | null, job: null as ImageJob | null, releaseSlot: null as (() => void) | null}),
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
        cancelled: false,
        start: release => {
          this.releaseSlot = release
          this.active = true
        },
      }
      imageQueue.push(this.job)
      pumpQueue()
    },
    finish() {
      this.releaseSlot?.()
      this.releaseSlot = null
    },
  },
})
</script>

<style scoped>
.lazy-image { display: inline-block; overflow: hidden; background: var(--v-contrast-1-base); }
.lazy-placeholder { display: block; width: 100%; height: 100%; background: var(--v-contrast-1-base); }
</style>
