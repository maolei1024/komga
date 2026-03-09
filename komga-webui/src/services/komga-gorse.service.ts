import { AxiosInstance } from 'axios'

const API_GORSE = '/api/v1/gorse'

export interface GorseSettingsDto {
    enabled: boolean
    apiUrl: string
    apiKey: string
    feedbackType: string
    positiveFeedbackType: string
    anonymousUserId: string
    readThreshold: number
}

export interface GorseSettingsUpdateDto {
    enabled?: boolean
    apiUrl?: string
    apiKey?: string
    feedbackType?: string
    positiveFeedbackType?: string
    anonymousUserId?: string
    readThreshold?: number
}

export interface GorseSyncResultDto {
    type: string
    count: number
}

export default class KomgaGorseService {
    private http: AxiosInstance

    constructor(http: AxiosInstance) {
        this.http = http
    }

    async getSettings(): Promise<GorseSettingsDto> {
        try {
            return (await this.http.get(API_GORSE)).data
        } catch (e) {
            let msg = 'An error occurred while trying to retrieve Gorse settings'
            if (e.response?.data?.message) {
                msg += `: ${e.response.data.message}`
            }
            throw new Error(msg)
        }
    }

    async updateSettings(settings: GorseSettingsUpdateDto) {
        try {
            await this.http.patch(API_GORSE, settings)
        } catch (e) {
            let msg = 'An error occurred while trying to update Gorse settings'
            if (e.response?.data?.message) {
                msg += `: ${e.response.data.message}`
            }
            throw new Error(msg)
        }
    }

    async syncItems(): Promise<GorseSyncResultDto> {
        try {
            return (await this.http.post(`${API_GORSE}/sync/items`)).data
        } catch (e) {
            let msg = 'An error occurred while syncing items to Gorse'
            if (e.response?.data?.message) {
                msg += `: ${e.response.data.message}`
            }
            throw new Error(msg)
        }
    }

    async syncUsers(): Promise<GorseSyncResultDto> {
        try {
            return (await this.http.post(`${API_GORSE}/sync/users`)).data
        } catch (e) {
            let msg = 'An error occurred while syncing users to Gorse'
            if (e.response?.data?.message) {
                msg += `: ${e.response.data.message}`
            }
            throw new Error(msg)
        }
    }

    async syncFeedback(): Promise<GorseSyncResultDto> {
        try {
            return (await this.http.post(`${API_GORSE}/sync/feedback`)).data
        } catch (e) {
            let msg = 'An error occurred while syncing feedback to Gorse'
            if (e.response?.data?.message) {
                msg += `: ${e.response.data.message}`
            }
            throw new Error(msg)
        }
    }

    async getLikeStatus(seriesId: string): Promise<{ liked: boolean }> {
        try {
            return (await this.http.get(`${API_GORSE}/like/${seriesId}`)).data
        } catch (e) {
            return { liked: false }
        }
    }

    async likeSeries(seriesId: string): Promise<void> {
        await this.http.put(`${API_GORSE}/like/${seriesId}`)
    }

    async unlikeSeries(seriesId: string): Promise<void> {
        await this.http.delete(`${API_GORSE}/like/${seriesId}`)
    }

    async getLikeStatusByBook(bookId: string): Promise<{ liked: boolean; seriesId: string }> {
        try {
            return (await this.http.get(`${API_GORSE}/like/book/${bookId}`)).data
        } catch (e) {
            return { liked: false, seriesId: '' }
        }
    }
}
