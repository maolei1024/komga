<template>
  <div class="gorse-choices" @keydown="navigate">
    <v-card v-for="item in items" :key="item.id" class="gorse-choice">
      <button
        type="button"
        class="gorse-choice-open"
        data-gorse-choice
        :disabled="busy || unavailable(item)"
        @click="$emit('choose', item)"
      >
        <v-img :src="thumbnail(item)" aspect-ratio="0.7071" :class="{blur: blur(item)}"/>
        <span class="d-block pa-2 text-body-2">{{ item.metadata.title }}</span>
        <span class="d-block px-2 pb-2 text-caption">
          {{ subtitle(item) }}
        </span>
      </button>
      <v-card-actions>
        <v-btn text small :to="details(item)">{{ $t('gorse_reading.details') }}</v-btn>
      </v-card-actions>
    </v-card>
  </div>
</template>

<script lang="ts">
import Vue from 'vue'
import {BookDto} from '@/types/komga-books'
import {SeriesDto} from '@/types/komga-series'
import {CLIENT_SETTING} from '@/types/komga-clientsettings'
import {bookThumbnailUrl, seriesThumbnailUrl} from '@/functions/urls'
import {isGorseReadable} from '@/services/gorse-reading'

export default Vue.extend({
  name: 'GorseChoices',
  props: {
    items: {type: Array as () => (SeriesDto | BookDto)[], required: true},
    busy: {type: Boolean, default: false},
  },
  methods: {
    thumbnail(item: SeriesDto | BookDto): string {
      return 'seriesId' in item ? bookThumbnailUrl(item.id) : seriesThumbnailUrl(item.id)
    },
    unavailable(item: SeriesDto | BookDto): boolean {
      return 'seriesId' in item && !isGorseReadable(item)
    },
    subtitle(item: SeriesDto | BookDto): string {
      if (!('seriesId' in item)) return this.$t('gorse_reading.book_count', {count: item.booksCount}).toString()
      if (!isGorseReadable(item)) return this.$t('gorse_reading.unavailable_short').toString()
      return this.$t('gorse_reading.book_number', {number: item.metadata.number}).toString()
    },
    details(item: SeriesDto | BookDto): object {
      if (item.oneshot) return {name: 'browse-oneshot', params: {seriesId: 'seriesId' in item ? item.seriesId : item.id}}
      return 'seriesId' in item
        ? {name: 'browse-book', params: {bookId: item.id}}
        : {name: 'browse-series', params: {seriesId: item.id}}
    },
    blur(item: SeriesDto | BookDto): boolean {
      const enabled = this.$store.getters.getClientSettings?.[CLIENT_SETTING.WEBUI_POSTER_BLUR_UNREAD]?.value === 'true'
      return enabled && ('seriesId' in item ? !item.readProgress : item.booksCount === item.booksUnreadCount)
    },
    buttons(): HTMLButtonElement[] {
      return Array.from(this.$el.querySelectorAll<HTMLButtonElement>('[data-gorse-choice]:not(:disabled)'))
    },
    focusFirst() {
      this.buttons()[0]?.focus({preventScroll: true})
    },
    navigate(event: KeyboardEvent) {
      if (event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return
      if (!['ArrowLeft', 'ArrowRight', 'ArrowUp', 'ArrowDown'].includes(event.key)) return
      const buttons = this.buttons()
      const index = buttons.indexOf(event.target as HTMLButtonElement)
      if (index < 0) return
      event.preventDefault()
      event.stopPropagation()
      if (event.repeat) return
      const top = buttons[0].getBoundingClientRect().top
      const columns = buttons.filter(button => Math.abs(button.getBoundingClientRect().top - top) < 2).length || 1
      const offset = event.key === 'ArrowDown' ? columns : event.key === 'ArrowUp' ? -columns : event.key === 'ArrowRight' ? 1 : -1
      buttons[Math.max(0, Math.min(buttons.length - 1, index + offset))]?.focus()
    },
  },
})
</script>

<style scoped>
.gorse-choices {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 16px;
}

.gorse-choice {
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}

.gorse-choice-open {
  width: 100%;
  text-align: start;
  color: inherit;
  border-radius: inherit;
}

.gorse-choice-open:focus-visible {
  outline: 3px solid var(--v-primary-base);
  outline-offset: 2px;
}

.gorse-choice-open:disabled {
  opacity: 0.6;
  cursor: default;
}

@media (max-width: 600px) {
  .gorse-choices {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 12px;
  }
}
</style>
