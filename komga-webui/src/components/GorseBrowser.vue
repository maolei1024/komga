<template>
  <div @keydown.capture="handleEscape" :aria-busy="busy">
    <div class="d-flex align-center mb-4">
      <v-btn v-if="selectedSeries" icon :disabled="busy" @click="back" :aria-label="$t('gorse_reading.back')">
        <v-icon>mdi-arrow-left</v-icon>
      </v-btn>
      <h2 class="text-h6">{{ selectedSeries ? selectedSeries.metadata.title : $t('gorse_reading.title') }}</h2>
      <v-spacer/>
      <v-btn v-if="!selectedSeries" text :disabled="busy" @click="load(true)">{{ $t('gorse_reading.refresh') }}</v-btn>
    </div>
    <p v-if="selectedSeries">{{ $t('gorse_reading.choose_book') }}</p>
    <v-progress-linear v-if="busy" indeterminate class="mb-4"/>
    <v-alert v-if="error" type="error" role="alert">{{ $t(`gorse_reading.${error}`) }}</v-alert>
    <gorse-choices ref="choices" :items="visibleItems" :busy="busy" @choose="choose"/>
    <p v-if="!busy && !error && !visibleItems.length" class="text-center pa-8">{{ $t('gorse_reading.empty') }}</p>
    <v-btn v-if="visibleCount < items.length" block text class="mt-4" :disabled="busy" @click="showMore">
      {{ $t('gorse_reading.more') }}
    </v-btn>
    <v-btn v-if="error" text :disabled="busy" @click="retry">{{ $t('gorse_reading.retry') }}</v-btn>
  </div>
</template>

<script lang="ts">
import Vue from 'vue'
import GorseChoices from '@/components/GorseChoices.vue'
import {BookDto} from '@/types/komga-books'
import {SeriesDto} from '@/types/komga-series'
import {GORSE_BATCH_SIZE, GorseReadingError, GorseReadingSession, isGorseReadable} from '@/services/gorse-reading'
import {seriesThumbnailUrl} from '@/functions/urls'

export default Vue.extend({
  name: 'GorseBrowser',
  components: {GorseChoices},
  props: {
    session: {type: Object as () => GorseReadingSession, required: true},
    initialSeries: {type: Object as () => SeriesDto, default: undefined},
    openBook: {type: Function, required: true},
    selectInDialog: {type: Boolean, default: false},
    autofocus: {type: Boolean, default: false},
  },
  data() {
    return {
      recommendations: [] as SeriesDto[],
      books: [] as BookDto[],
      selectedSeries: undefined as SeriesDto | undefined,
      visibleCount: GORSE_BATCH_SIZE,
      busy: false,
      error: '',
      requestVersion: 0,
      retryAction: undefined as (() => Promise<void>) | undefined,
    }
  },
  computed: {
    items(): (SeriesDto | BookDto)[] {
      return this.selectedSeries ? this.books : this.recommendations
    },
    visibleItems(): (SeriesDto | BookDto)[] {
      return this.items.slice(0, this.visibleCount)
    },
  },
  mounted() {
    if (this.initialSeries) this.selectSeries(this.initialSeries)
    else this.load()
  },
  beforeDestroy() {
    this.requestVersion++
  },
  methods: {
    async perform(action: (version: number) => Promise<void>, retry: () => Promise<void>) {
      if (this.busy) return
      const version = ++this.requestVersion
      this.busy = true
      this.error = ''
      this.retryAction = retry
      try {
        await action(version)
      } catch (error) {
        if (version === this.requestVersion) this.error = error instanceof GorseReadingError ? error.key : 'load_failed'
      } finally {
        if (version === this.requestVersion) {
          this.busy = false
          await this.$nextTick()
          if (this.autofocus) (this.$refs.choices as InstanceType<typeof GorseChoices>)?.focusFirst()
        }
      }
    },
    async load(refresh = false) {
      await this.perform(async version => {
        const recommendations = await this.session.recommendations(refresh)
        if (version !== this.requestVersion) return
        this.recommendations = recommendations
        this.visibleCount = GORSE_BATCH_SIZE
        this.prefetch()
      }, () => this.load(refresh))
    },
    choose(item: SeriesDto | BookDto) {
      if ('seriesId' in item) this.read(item)
      else if (this.selectInDialog) this.$emit('series', item)
      else this.selectSeries(item)
    },
    async selectSeries(series: SeriesDto) {
      await this.perform(async version => {
        this.selectedSeries = series
        this.books = []
        this.visibleCount = GORSE_BATCH_SIZE
        const books = await this.session.books(series.id)
        if (version !== this.requestVersion) return
        this.books = books
        if (!books.some(isGorseReadable)) throw new GorseReadingError('unavailable')
        if (books.length === 1) await this.prepareAndOpen(books[0], version)
      }, () => this.selectSeries(series))
    },
    async prepareAndOpen(book: BookDto, version: number) {
      const prepared = await this.session.prepareBook(book.id)
      if (version !== this.requestVersion) return
      await this.openBook(prepared)
    },
    async read(book: BookDto) {
      await this.perform(version => this.prepareAndOpen(book, version), () => this.read(book))
    },
    retry() {
      this.retryAction?.()
    },
    async back() {
      if (this.busy) return
      if (this.initialSeries) this.$emit('close')
      else {
        this.selectedSeries = undefined
        this.books = []
        this.error = ''
        await this.load()
      }
    },
    handleEscape(event: KeyboardEvent) {
      if (event.repeat && event.key === 'Enter') {
        event.preventDefault()
        event.stopImmediatePropagation()
      }
      if (event.key !== 'Escape') return
      event.preventDefault()
      event.stopImmediatePropagation()
      this.$emit('close')
    },
    showMore() {
      this.visibleCount += GORSE_BATCH_SIZE
      this.prefetch()
    },
    prefetch() {
      if (this.selectedSeries) return
      this.recommendations.slice(this.visibleCount, this.visibleCount + GORSE_BATCH_SIZE).forEach(series => {
        const image = new Image()
        image.src = seriesThumbnailUrl(series.id)
      })
    },
  },
})
</script>
