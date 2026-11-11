<template>
  <div>
    <div class="text-subtitle-2">{{ label }}</div>
    <svg ref="plot" class="preference-curve" viewBox="0 0 400 120" role="img" :aria-label="label"
         @pointermove="move" @pointerup="finish" @pointercancel="finish">
      <line x1="20" y1="100" x2="380" y2="100" stroke="currentColor" opacity=".3"/>
      <path :d="curve" fill="rgba(33,150,243,.18)" stroke="#2196f3" stroke-width="2"/>
      <line :x1="centerX" y1="20" :x2="centerX" y2="100" stroke="#2196f3" stroke-dasharray="3 3"/>
      <circle :cx="centerX" cy="20" r="8" fill="#1976d2" tabindex="0" aria-label="拖动偏好中心"
              @pointerdown.prevent="start('mean', $event)" @keydown.left.prevent="adjust('mean', -1)" @keydown.right.prevent="adjust('mean', 1)"/>
      <circle :cx="widthX" cy="52" r="7" fill="#ff9800" tabindex="0" aria-label="拖动偏好范围"
              @pointerdown.prevent="start('deviation', $event)" @keydown.left.prevent="adjust('deviation', -1)" @keydown.right.prevent="adjust('deviation', 1)"/>
      <text x="20" y="117" font-size="10" fill="currentColor">{{ min }}</text>
      <text x="360" y="117" font-size="10" fill="currentColor">{{ Math.round(domainMax) }}</text>
    </svg>
    <v-row dense>
      <v-col><v-text-field :value="value.mean" type="number" :min="min" label="中心" dense outlined
                          @input="update('mean', Number($event))" @change="$emit('change')"/></v-col>
      <v-col><v-text-field :value="value.deviation" type="number" min=".1" step=".5" label="范围（标准差）" dense outlined
                          @input="update('deviation', Number($event))" @change="$emit('change')"/></v-col>
    </v-row>
  </div>
</template>

<script lang="ts">
import Vue, {PropType} from 'vue'
import {PreferenceAxis} from '@/services/gorse-preference-profile'

export default Vue.extend({
  name: 'PreferenceGaussianCurve',
  props: {
    value: {type: Object as PropType<PreferenceAxis>, required: true},
    label: {type: String, required: true},
    min: {type: Number, default: 0},
    max: {type: Number, default: 100},
  },
  data: () => ({dragging: '' as '' | 'mean' | 'deviation', dragMax: 0}),
  computed: {
    domainMax(): number { return this.dragging ? this.dragMax : Math.max(this.max, this.value.mean + this.value.deviation * 3) },
    centerX(): number { return this.position(this.value.mean) },
    widthX(): number { return this.position(this.value.mean + this.value.deviation) },
    curve(): string {
      const points = Array.from({length: 81}, (_, index) => {
        const value = this.min + index / 80 * (this.domainMax - this.min)
        const height = Math.exp(-.5 * Math.pow((value - this.value.mean) / Math.max(.1, this.value.deviation), 2))
        return `${this.position(value)},${100 - height * 80}`
      })
      return 'M20,100 L' + points.join(' L') + ' L380,100 Z'
    },
  },
  methods: {
    position(value: number): number { return 20 + (value - this.min) / (this.domainMax - this.min) * 360 },
    update(field: 'mean' | 'deviation', value: number) { this.$emit('input', {...this.value, [field]: value}) },
    start(field: 'mean' | 'deviation', event: PointerEvent) {
      this.dragMax = this.domainMax
      this.dragging = field
      ;(this.$refs.plot as SVGSVGElement).setPointerCapture(event.pointerId)
    },
    move(event: PointerEvent) {
      if (!this.dragging) return
      const rectangle = (this.$refs.plot as SVGSVGElement).getBoundingClientRect()
      const fraction = ((event.clientX - rectangle.left) / rectangle.width * 400 - 20) / 360
      const value = this.min + Math.max(0, Math.min(1, fraction)) * (this.domainMax - this.min)
      this.update(this.dragging, Math.round((this.dragging === 'mean' ? Math.max(this.min, value) : Math.max(.1, Math.abs(value - this.value.mean))) * 10) / 10)
    },
    finish() { if (this.dragging) { this.dragging = ''; this.$emit('change') } },
    adjust(field: 'mean' | 'deviation', amount: number) {
      this.update(field, Math.max(field === 'mean' ? this.min : .1, this.value[field] + amount))
      this.$emit('change')
    },
  },
})
</script>

<style scoped>
.preference-curve { width: 100%; height: 150px; touch-action: none; }
circle { cursor: ew-resize; }
</style>
