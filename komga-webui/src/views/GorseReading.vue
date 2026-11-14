<template>
  <v-container fluid>
    <gorse-browser v-if="session" :session="session" :open-book="openBook" select-in-dialog @series="chooseSeries"/>
    <gorse-picker-dialog v-if="session" v-model="picker" :session="session" :initial-series="selectedSeries" :open-book="openBook"/>
  </v-container>
</template>

<script lang="ts">
import Vue from 'vue'
import GorseBrowser from '@/components/GorseBrowser.vue'
import GorsePickerDialog from '@/components/dialogs/GorsePickerDialog.vue'
import {getGorseReading, GorseReadingSession, PreparedGorseBook} from '@/services/gorse-reading'
import {SeriesDto} from '@/types/komga-series'

export default Vue.extend({
  name: 'GorseReading',
  components: {GorseBrowser, GorsePickerDialog},
  data() {
    return {session: undefined as GorseReadingSession | undefined, selectedSeries: undefined as SeriesDto | undefined, picker: false}
  },
  created() {
    this.session = getGorseReading(this.$store.state.komgaUsers.me.id, this.$komgaSeries, this.$komgaBooks, true)
  },
  methods: {
    chooseSeries(series: SeriesDto) {
      this.selectedSeries = series
      this.picker = true
    },
    async openBook(prepared: PreparedGorseBook) {
      await this.$router.push({name: 'read-book', params: {bookId: prepared.book.id}, query: {mode: 'gorse'}})
    },
  },
})
</script>
