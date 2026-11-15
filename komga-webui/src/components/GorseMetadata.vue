<template>
  <span class="gorse-metadata d-block text-truncate text-caption" :title="summary">{{ summary }}</span>
</template>

<script lang="ts">
import Vue from 'vue'
import {BookDto} from '@/types/komga-books'
import {SeriesDto} from '@/types/komga-series'
import {gorseMetadata} from '@/functions/gorse-metadata'

export default Vue.extend({
  name: 'GorseMetadata',
  props: {
    item: {type: Object as () => SeriesDto | BookDto, required: true},
  },
  computed: {
    summary(): string {
      return this.$t('gorse_reading.metadata', gorseMetadata(this.item)).toString()
    },
  },
})
</script>

<style scoped>
.gorse-metadata {
  min-width: 0;
  max-width: 100%;
  white-space: nowrap;
}
</style>
