<template>
  <section class="gorse-reader-feedback-panel" data-gorse-feedback @keydown="keyPressed" @click.stop>
    <div class="gorse-feedback-summary">
      <div class="d-flex align-center">
        <strong class="text-truncate flex-grow-1" :title="state.title">{{ state.title }}</strong>
        <v-btn small text data-feedback-details-toggle :aria-expanded="state.expanded" @click="state.toggleDetails()">
          {{ $t(state.expanded ? 'gorse_feedback.collapse' : 'gorse_feedback.details') }}
          <v-icon small>{{ state.expanded ? 'mdi-chevron-up' : 'mdi-chevron-down' }}</v-icon>
        </v-btn>
      </div>
      <div class="text-caption text-truncate" :title="summary">{{ summary }}</div>
      <v-progress-linear v-if="state.contextLoading" indeterminate height="2"/>
    </div>
    <template v-if="state.context && state.context.featuresStatus === 'AVAILABLE'">
      <strong id="gorse-content-tags-label" class="gorse-feedback-tag-label text-caption font-weight-medium">
        {{ $t('gorse_feedback.tags', {count: state.context.tags.length}) }}
      </strong>
      <ul v-if="displayTags.length" class="gorse-feedback-tags gorse-feedback-scroll" tabindex="0" aria-labelledby="gorse-content-tags-label">
        <li v-for="(tag, index) in displayTags" :key="state.context.tags[index]">{{ tag }}</li>
      </ul>
      <span v-else class="text-caption">{{ $t('gorse_feedback.no_tags') }}</span>
    </template>
    <div v-if="state.expanded" class="gorse-feedback-details gorse-feedback-scroll" tabindex="0">
      <dl>
        <template v-for="field in fields">
          <dt :key="field.key + '-label'">{{ $t('gorse_feedback.' + field.key) }}</dt>
          <dd :key="field.key">{{ field.value }}</dd>
        </template>
      </dl>
      <p class="text-caption mt-2 mb-0">{{ $t('gorse_feedback.explanation') }}</p>
    </div>
    <div v-if="state.contextFailed" class="gorse-feedback-status text-caption" role="status">
      {{ $t(state.context && state.context.featuresStatus === 'MISSING' ? 'gorse_feedback.features_missing' : 'gorse_feedback.features_failed') }}
      <v-btn text x-small :loading="state.contextLoading" @click="state.loadContext()">{{ $t('gorse_reading.retry') }}</v-btn>
    </div>
    <div v-if="state.preferenceFailed" class="gorse-feedback-status text-caption" role="alert">
      {{ $t('gorse_feedback.preference_failed') }}
      <v-btn text x-small :loading="state.preferenceLoading" @click="state.loadPreference()">{{ $t('gorse_reading.retry') }}</v-btn>
    </div>
    <div v-if="state.saveError" class="gorse-feedback-status text-caption" role="alert">
      {{ $t('gorse_feedback.' + state.saveError) }}
      <v-btn text x-small :disabled="disabled || state.saving" @click="state.retry()">{{ $t('gorse_reading.retry') }}</v-btn>
    </div>
    <div v-if="!state.incognito" class="gorse-feedback-actions">
      <v-btn v-for="choice in choices" :key="choice.value" small outlined
             :data-feedback="choice.value" :aria-pressed="state.preference === choice.value"
             :aria-label="$t(state.preference === choice.value ? choice.cancel : choice.label)"
             :color="state.preference === choice.value ? 'primary' : undefined"
             :disabled="disabled || state.saving || !state.preferenceReady"
             :loading="state.saving && (state.pending === choice.value || (state.pending === 'NONE' && state.preference === choice.value))"
             @click="state.toggle(choice.value)">
        <v-icon small left>{{ choice.icon }}</v-icon>{{ $t(choice.label) }}
      </v-btn>
      <span v-if="state.saved" class="text-caption" role="status">{{ $t('gorse_feedback.saved') }}</span>
    </div>
  </section>
</template>

<script lang="ts">
import Vue from 'vue'
import {GorseReaderFeedback} from '@/services/gorse-reader-feedback'

export default Vue.extend({
  name: 'GorseReaderFeedback',
  props: {
    state: {type: Object as () => GorseReaderFeedback, required: true},
    disabled: {type: Boolean, default: false},
  },
  data: () => ({
    choices: [
      {value: 'LIKE' as const, label: 'gorse_feedback.like', cancel: 'gorse_feedback.cancel_like', icon: 'mdi-heart-outline'},
      {value: 'DISLIKE' as const, label: 'gorse_feedback.dislike', cancel: 'gorse_feedback.cancel_dislike', icon: 'mdi-thumb-down-outline'},
    ],
  }),
  computed: {
    unknown(): string { return this.$t('gorse_feedback.unknown').toString() },
    summary(): string {
      const context = this.state.context
      return this.$t('gorse_feedback.summary', {pages: context?.pagesCount ?? '—', tags: context?.tagCount ?? '—'}).toString()
    },
    displayTags(): string[] {
      return this.state.context?.displayTags ?? this.state.context?.tags ?? []
    },
    fields(): {key: string, value: string}[] {
      const context = this.state.context
      return (['authors', 'pageBuckets', 'tagBuckets', 'genres', 'groups', 'characters'] as const)
        .map(key => ({key, value: this.values(context?.[key])}))
    },
  },
  methods: {
    values(values?: string[]): string { return values?.length ? values.join(' · ') : this.unknown },
    keyPressed(event: KeyboardEvent) {
      event.stopPropagation()
      const target = event.target as HTMLElement | null
      const scrollArea = target?.closest<HTMLElement>('.gorse-feedback-scroll')
      if (event.repeat && ['Enter', ' '].includes(event.key)) event.preventDefault()
      if (event.key === ' ' && !target?.closest?.('button, a[href]')) {
        event.preventDefault()
        if (scrollArea) scrollArea.scrollTop += (event.shiftKey ? -1 : 1) * scrollArea.clientHeight
      }
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight', 'PageUp', 'PageDown', 'Home', 'End'].includes(event.key)) {
        event.preventDefault()
        if (scrollArea) {
          if (event.key === 'Home') scrollArea.scrollTop = 0
          else if (event.key === 'End') scrollArea.scrollTop = scrollArea.scrollHeight
          else {
            const distance = event.key.startsWith('Page') ? scrollArea.clientHeight : 40
            if (['ArrowUp', 'PageUp'].includes(event.key)) scrollArea.scrollTop -= distance
            if (['ArrowDown', 'PageDown'].includes(event.key)) scrollArea.scrollTop += distance
          }
        }
      }
      if (event.key === 'Escape') {
        event.preventDefault()
        if (this.state.expanded) this.state.toggleDetails()
        else if (!this.state.saving) this.$emit('close')
      }
    },
  },
})
</script>

<style scoped>
.gorse-reader-feedback-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  max-height: 70vh;
  max-height: 70dvh;
  padding: 8px max(12px, env(safe-area-inset-right)) 8px max(12px, env(safe-area-inset-left));
  background: var(--v-background-base, #fff);
  color: rgba(0, 0, 0, .87);
  box-shadow: 0 2px 8px rgba(0, 0, 0, .2);
}
.theme--dark .gorse-reader-feedback-panel {
  background: #212121;
  color: white;
}
.gorse-feedback-summary, .gorse-feedback-actions, .gorse-feedback-status { flex: 0 0 auto; }
.gorse-feedback-summary strong { min-width: 0; }
.gorse-feedback-scroll { min-height: 0; overflow-y: auto; overscroll-behavior: contain; }
.gorse-feedback-scroll:focus-visible { outline: 2px solid var(--v-primary-base, currentColor); outline-offset: 1px; }
.gorse-feedback-tag-label { margin: 4px 0; }
.gorse-feedback-tags {
  display: flex;
  flex-wrap: wrap;
  align-content: flex-start;
  gap: .25rem .375rem;
  max-height: 5.75rem;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: .8125rem;
  line-height: 1.25rem;
}
.gorse-feedback-tags li {
  max-width: 100%;
  padding: 0 6px;
  border-radius: 4px;
  background: rgba(127, 127, 127, .14);
  overflow-wrap: anywhere;
}
.gorse-feedback-details dl { margin: 8px 0; }
.gorse-feedback-details dt { font-weight: 600; font-size: 12px; }
.gorse-feedback-details dd { margin: 0 0 8px; overflow-wrap: anywhere; font-size: 13px; }
.gorse-feedback-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding-top: 8px; }
</style>
