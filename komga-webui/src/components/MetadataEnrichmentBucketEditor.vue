<template>
  <v-simple-table dense>
    <thead>
    <tr>
      <th>起始</th>
      <th>结束</th>
      <th>标签</th>
    </tr>
    </thead>
    <tbody>
    <tr v-for="(bucket, index) in buckets" :key="index">
      <td>
        <v-text-field v-model.number="bucket.min" type="number" dense hide-details @input="$emit('changed')"/>
      </td>
      <td>
        <v-text-field
          v-model.number="bucket.max"
          type="number"
          dense
          hide-details
          placeholder="无上限"
          @input="$emit('changed')"
        />
      </td>
      <td>
        <v-text-field v-model="bucket.label" dense hide-details @input="$emit('changed')"/>
      </td>
    </tr>
    </tbody>
  </v-simple-table>
</template>

<script lang="ts">
import Vue from 'vue'

export default Vue.extend({
  name: 'MetadataEnrichmentBucketEditor',
  props: {
    buckets: {type: Array, required: true},
  },
})
</script>
