import {AxiosInstance} from 'axios'
import _Vue from 'vue'
import KomgaMetadataEnrichmentService from '@/services/komga-metadata-enrichment.service'

export default {
  install(
    Vue: typeof _Vue,
    {http}: { http: AxiosInstance },
  ) {
    Vue.prototype.$komgaMetadataEnrichment = new KomgaMetadataEnrichmentService(http)
  },
}

declare module 'vue/types/vue' {
  interface Vue {
    $komgaMetadataEnrichment: KomgaMetadataEnrichmentService;
  }
}
