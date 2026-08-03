import {AxiosInstance} from 'axios'
import {
  DedupCaseOrigin,
  DedupReviewCaseDto,
  DedupSettingsDto,
  DedupStatusDto,
  DedupDecisionDto,
  DedupPageComparisonDto,
} from '@/types/komga-dedup'

const API_DEDUP = '/api/v1/dedup'

export default class KomgaDedupService {
  private http: AxiosInstance

  constructor(http: AxiosInstance) {
    this.http = http
  }

  async getSettings(): Promise<DedupSettingsDto> {
    return (await this.http.get(`${API_DEDUP}/settings`)).data
  }

  async updateSettings(settings: DedupSettingsDto): Promise<DedupSettingsDto> {
    return (await this.http.put(`${API_DEDUP}/settings`, settings)).data
  }

  async getStatus(): Promise<DedupStatusDto> {
    return (await this.http.get(`${API_DEDUP}/status`)).data
  }

  async getCases(params: {
    page: number
    size: number
    library_id?: string
    origin?: DedupCaseOrigin
  }): Promise<Page<DedupReviewCaseDto>> {
    return (await this.http.get(`${API_DEDUP}/cases`, {params})).data
  }

  async requestScan(libraryIds: string[] = []): Promise<{requestedLibraries: number}> {
    return (await this.http.post(`${API_DEDUP}/scans`, {libraryIds})).data
  }

  async pause(libraryIds: string[] = []): Promise<DedupSettingsDto> {
    return (await this.http.post(`${API_DEDUP}/scans/pause`, {libraryIds})).data
  }

  async resume(libraryIds: string[] = []): Promise<DedupSettingsDto> {
    return (await this.http.post(`${API_DEDUP}/scans/resume`, {libraryIds})).data
  }

  async setKeeper(caseId: string, expectedRevision: number, bookId: string): Promise<DedupReviewCaseDto> {
    return (await this.http.put(`${API_DEDUP}/cases/${caseId}/keeper`, {expectedRevision, bookId})).data
  }

  async addOverride(
    caseId: string,
    request: {type: string; expectedRevision: number; bookId?: string; reason?: string},
  ): Promise<DedupReviewCaseDto> {
    return (await this.http.post(`${API_DEDUP}/cases/${caseId}/overrides`, request)).data
  }

  async reanalyze(caseId: string): Promise<void> {
    await this.http.post(`${API_DEDUP}/cases/${caseId}/verify`)
  }

  async getPageComparison(caseId: string): Promise<DedupPageComparisonDto> {
    return (await this.http.get(`${API_DEDUP}/cases/${caseId}/pages`)).data
  }

  async createSuggestedDecision(caseId: string, expectedRevision: number, stateRevision: string): Promise<DedupDecisionDto> {
    return (await this.http.post(`${API_DEDUP}/cases/${caseId}/decisions/suggest`, {expectedRevision, stateRevision})).data
  }

  async createCustomDecision(
    caseId: string,
    request: {
      expectedRevision: number
      keeperBookId: string
      removeBookIds: string[]
      stateRevision: string
      acknowledgedReasonCodes: string[]
    },
  ): Promise<DedupDecisionDto> {
    return (await this.http.post(`${API_DEDUP}/cases/${caseId}/decisions/custom`, request)).data
  }

  async executeDecision(decisionId: string): Promise<DedupDecisionDto> {
    return (await this.http.post(`${API_DEDUP}/decisions/${decisionId}/execute`)).data
  }

  async getDecision(decisionId: string): Promise<DedupDecisionDto> {
    return (await this.http.get(`${API_DEDUP}/decisions/${decisionId}`)).data
  }

  async getDecisions(page = 0, size = 20): Promise<Page<DedupDecisionDto>> {
    return (await this.http.get(`${API_DEDUP}/decisions`, {params: {page, size}})).data
  }
}
