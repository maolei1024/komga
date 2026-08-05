import {AxiosInstance} from 'axios'
import {Page} from '@/types/komga-api'
import {
  DedupClusterDetailDto,
  DedupClusterSummaryDto,
  DedupPageComparisonDto,
  DedupResolutionDto,
  DedupSettingsDto,
  DedupStatusDto,
} from '@/types/komga-dedup'

const API_DEDUP = '/api/v1/dedup'

export default class KomgaDedupService {
  constructor(private http: AxiosInstance) {}

  async getSettings(): Promise<DedupSettingsDto> { return (await this.http.get(`${API_DEDUP}/settings`)).data }
  async updateSettings(settings: DedupSettingsDto): Promise<DedupSettingsDto> { return (await this.http.put(`${API_DEDUP}/settings`, settings)).data }
  async getStatus(): Promise<DedupStatusDto> { return (await this.http.get(`${API_DEDUP}/status`)).data }
  async requestScan(libraryIds: string[] = []): Promise<{requestedLibraries: number}> {
    return (await this.http.post(`${API_DEDUP}/scans`, {libraryIds})).data
  }

  async getClusters(params: {page: number; size: number; library_id?: string}, signal?: AbortSignal): Promise<Page<DedupClusterSummaryDto>> {
    return (await this.http.get(`${API_DEDUP}/clusters`, {params, signal})).data
  }
  async getCluster(clusterId: string, signal?: AbortSignal): Promise<DedupClusterDetailDto> {
    return (await this.http.get(`${API_DEDUP}/clusters/${clusterId}`, {signal})).data
  }
  async getPageComparison(clusterId: string, leftBookId: string, rightBookId: string): Promise<DedupPageComparisonDto> {
    return (await this.http.get(`${API_DEDUP}/clusters/${clusterId}/pages`, {params: {left_book_id: leftBookId, right_book_id: rightBookId}})).data
  }
  async createSuggestedResolution(clusterId: string, expectedRevision: number): Promise<DedupResolutionDto> {
    return (await this.http.post(`${API_DEDUP}/clusters/${clusterId}/resolutions/suggested`, {expectedRevision})).data
  }
  async createCustomResolution(clusterId: string, expectedRevision: number, deleteBookIds: string[]): Promise<DedupResolutionDto> {
    return (await this.http.post(`${API_DEDUP}/clusters/${clusterId}/resolutions/custom`, {expectedRevision, deleteBookIds})).data
  }
  async retryResolution(resolutionId: string): Promise<DedupResolutionDto> {
    return (await this.http.post(`${API_DEDUP}/resolutions/${resolutionId}/retry`)).data
  }
  async getResolution(resolutionId: string): Promise<DedupResolutionDto> {
    return (await this.http.get(`${API_DEDUP}/resolutions/${resolutionId}`)).data
  }
  async getResolutions(page = 0, size = 20): Promise<Page<DedupResolutionDto>> {
    return (await this.http.get(`${API_DEDUP}/resolutions`, {params: {page, size}})).data
  }
}
