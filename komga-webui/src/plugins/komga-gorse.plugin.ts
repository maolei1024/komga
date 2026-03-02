import { AxiosInstance } from 'axios'
import _Vue from 'vue'
import KomgaGorseService from '@/services/komga-gorse.service'

export default {
    install(
        Vue: typeof _Vue,
        { http }: { http: AxiosInstance }) {
        Vue.prototype.$komgaGorse = new KomgaGorseService(http)
    },
}

declare module 'vue/types/vue' {
    interface Vue {
        $komgaGorse: KomgaGorseService;
    }
}
