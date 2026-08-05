<template>
  <section v-if="resolution" class="resolution-result">
    <h2>{{ $t('dedup.lastAttempt') }}</h2>
    <v-simple-table dense>
      <thead><tr><th>{{ $t('dedup.book') }}</th><th>{{ $t('dedup.action') }}</th><th>{{ $t('dedup.result') }}</th></tr></thead>
      <tbody>
        <tr v-for="member in resolution.members" :key="member.bookId">
          <td>{{ member.title }}</td>
          <td>{{ $t(`dedup.actionValue.${member.action}`) }}</td>
          <td :class="{'error--text': member.lastError}">{{ member.lastError || member.resultCode || member.state }}</td>
        </tr>
      </tbody>
    </v-simple-table>
  </section>
</template>

<script lang="ts">
import Vue from 'vue'
import {DedupResolutionDto} from '@/types/komga-dedup'
export default Vue.extend({name: 'DedupResolutionResult', props: {resolution: {type: Object as () => DedupResolutionDto | null, default: null}}})
</script>

<style scoped>
.resolution-result { margin-top: 20px; }
.resolution-result h2 { margin: 0 0 10px; font-size: 1rem; }
</style>
