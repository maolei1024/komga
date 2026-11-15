<template>
  <v-dialog :value="value" @input="$emit('input', $event)" max-width="1100" scrollable
            :fullscreen="$vuetify.breakpoint.smAndDown">
    <v-card v-if="value" @keydown.stop :class="{'gorse-picker-mobile': $vuetify.breakpoint.smAndDown}">
      <v-card-actions class="gorse-picker-toolbar justify-end">
        <v-btn icon :aria-label="$t('gorse_reading.close')" @click="$emit('input', false)"><v-icon>mdi-close</v-icon></v-btn>
      </v-card-actions>
      <v-card-text class="gorse-picker-content">
        <gorse-browser
          :session="session"
          :initial-series="initialSeries"
          :open-book="openBook"
          autofocus
          @close="$emit('input', false)"
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
