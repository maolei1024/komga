import {AxiosInstance} from 'axios'
import _Vue from 'vue'
import KomgaDedupService from '@/services/komga-dedup.service'

export default {
  install(Vue: typeof _Vue, {http}: {http: AxiosInstance}) {
    Vue.prototype.$komgaDedup = new KomgaDedupService(http)
  },
}

declare module 'vue/types/vue' {
  interface Vue {
    $komgaDedup: KomgaDedupService;
  }
}
