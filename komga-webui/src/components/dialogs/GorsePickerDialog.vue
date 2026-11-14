<template>
  <v-dialog :value="value" @input="$emit('input', $event)" max-width="1100" scrollable>
    <v-card v-if="value" @keydown.stop>
      <v-card-actions class="justify-end">
        <v-btn icon :aria-label="$t('gorse_reading.close')" @click="$emit('input', false)"><v-icon>mdi-close</v-icon></v-btn>
      </v-card-actions>
      <v-card-text>
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
