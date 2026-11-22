<template>
  <v-dialog :value="value" @input="close" max-width="1100" scrollable :persistent="blocked" :retain-focus="true"
            :fullscreen="$vuetify.breakpoint.smAndDown">
    <v-card v-if="value" @keydown.stop :class="{'gorse-picker-mobile': $vuetify.breakpoint.smAndDown}">
      <v-card-actions class="gorse-picker-toolbar justify-end">
        <v-btn icon :disabled="blocked" :aria-label="$t('gorse_reading.close')" @click="close(false)"><v-icon>mdi-close</v-icon></v-btn>
      </v-card-actions>
      <div v-if="$scopedSlots.feedback" class="gorse-picker-feedback"><slot name="feedback"/></div>
      <v-card-text class="gorse-picker-content">
        <gorse-browser
          :session="session"
          :initial-series="initialSeries"
          :open-book="openBook"
          :blocked="blocked"
          autofocus
          @close="close(false)"
        />
      </v-card-text>
    </v-card>
  </v-dialog>
</template>

<script lang="ts">
import Vue from 'vue'
import GorseBrowser from '@/components/GorseBrowser.vue'
import {GorseReadingSession} from '@/services/gorse-reading'
import {SeriesDto} from '@/types/komga-series'

export default Vue.extend({
  name: 'GorsePickerDialog',
  components: {GorseBrowser},
  props: {
    value: {type: Boolean, required: true},
    session: {type: Object as () => GorseReadingSession, required: true},
    initialSeries: {type: Object as () => SeriesDto, default: undefined},
    openBook: {type: Function, required: true},
    blocked: {type: Boolean, default: false},
  },
  methods: {
    close(value: boolean) { if (!this.blocked) this.$emit('input', value) },
  },
})
</script>

<style scoped>
.gorse-picker-toolbar {
  flex: 0 0 auto;
}

.gorse-picker-content {
  min-height: 0;
  overscroll-behavior: contain;
}

.gorse-picker-feedback { flex: 0 0 auto; min-height: 0; }

.gorse-picker-mobile {
  height: 100vh;
  height: 100dvh;
  border-radius: 0;
}

.gorse-picker-mobile .gorse-picker-toolbar {
  padding-top: max(8px, env(safe-area-inset-top));
  padding-right: max(8px, env(safe-area-inset-right));
}

.gorse-picker-mobile .gorse-picker-content {
  padding: 0 max(12px, env(safe-area-inset-right)) max(16px, env(safe-area-inset-bottom)) max(12px, env(safe-area-inset-left));
}
</style>
