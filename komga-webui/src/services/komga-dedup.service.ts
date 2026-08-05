import {AxiosInstance} from 'axios'
import {
  DedupBulkVerificationResultDto,
  DedupClusterDetailDto,
  DedupClusterEligibilityBatchDto,
  DedupClusterProcessingDto,
  DedupClusterStatus,
  DedupClusterSummaryDto,
  DedupClusterVerificationRequestDto,
  DedupCustomResolutionMemberDto,
  DedupEvidenceMaturity,
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
  async requestScan(libraryIds: string[] = []): Promise<{requestedLibraries: number}> { return (await this.http.post(`${API_DEDUP}/scans`, {libraryIds})).data }
  async pause(libraryIds: string[] = []): Promise<DedupSettingsDto> { return (await this.http.post(`${API_DEDUP}/scans/pause`, {libraryIds})).data }
  async resume(libraryIds: string[] = []): Promise<DedupSettingsDto> { return (await this.http.post(`${API_DEDUP}/scans/resume`, {libraryIds})).data }

  async getClusters(params: {page: number; size: number; library_id?: string; status?: DedupClusterStatus; evidence?: DedupEvidenceMaturity}, signal?: AbortSignal): Promise<Page<DedupClusterSummaryDto>> {
    return (await this.http.get(`${API_DEDUP}/clusters`, {params, signal})).data
  }
  async getCluster(clusterId: string, signal?: AbortSignal): Promise<DedupClusterDetailDto> { return (await this.http.get(`${API_DEDUP}/clusters/${clusterId}`, {signal})).data }
  async getClusterEligibility(clusters: DedupClusterVerificationRequestDto[], signal?: AbortSignal): Promise<DedupClusterEligibilityBatchDto> {
    return (await this.http.post(`${API_DEDUP}/clusters/eligibility`, {clusters}, {signal})).data
  }
  async getClusterProcessing(clusterId: string, expectedRevision: number, signal?: AbortSignal): Promise<DedupClusterProcessingDto> {
    return (await this.http.get(`${API_DEDUP}/clusters/${clusterId}/processing`, {params: {expected_revision: expectedRevision}, signal})).data
  }
  async getPageComparison(clusterId: string, leftBookId: string, rightBookId: string): Promise<DedupPageComparisonDto> {
    return (await this.http.get(`${API_DEDUP}/clusters/${clusterId}/pages`, {params: {left_book_id: leftBookId, right_book_id: rightBookId}})).data
  }
  async verifyCluster(clusterId: string, expectedRevision: number): Promise<void> { await this.http.post(`${API_DEDUP}/clusters/${clusterId}/verify`, {expectedRevision}) }
  async verifyClusters(clusters: DedupClusterVerificationRequestDto[]): Promise<DedupBulkVerificationResultDto> {
    return (await this.http.post(`${API_DEDUP}/clusters/verify`, {clusters})).data
  }
  async createSuggestedResolution(clusterId: string, expectedRevision: number, stateRevision: string, planRevision: string): Promise<DedupResolutionDto> {
    return (await this.http.post(`${API_DEDUP}/clusters/${clusterId}/resolutions/suggested`, {expectedRevision, stateRevision, planRevision})).data
  }
  async createCustomResolution(clusterId: string, request: {expectedRevision: number; stateRevision: string; members: DedupCustomResolutionMemberDto[]; acknowledgedReasonCodes: string[]}): Promise<DedupResolutionDto> {
    return (await this.http.post(`${API_DEDUP}/clusters/${clusterId}/resolutions/custom`, request)).data
  }
  async retryResolution(resolutionId: string): Promise<DedupResolutionDto> { return (await this.http.post(`${API_DEDUP}/resolutions/${resolutionId}/retry`)).data }
  async abandonResolution(resolutionId: string): Promise<DedupResolutionDto> { return (await this.http.post(`${API_DEDUP}/resolutions/${resolutionId}/abandon`)).data }
  async getResolution(resolutionId: string): Promise<DedupResolutionDto> { return (await this.http.get(`${API_DEDUP}/resolutions/${resolutionId}`)).data }
  async getResolutions(page = 0, size = 20): Promise<Page<DedupResolutionDto>> { return (await this.http.get(`${API_DEDUP}/resolutions`, {params: {page, size}})).data }
}
