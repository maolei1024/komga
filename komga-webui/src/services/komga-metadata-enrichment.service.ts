import {AxiosInstance} from 'axios'

const API_METADATA_ENRICHMENT = '/api/v1/metadata-enrichment'

export type MetadataEnrichmentProcessor = 'AI_TITLE' | 'TAG_TRANSLATION' | 'PAGE_SIZE' | 'TAG_SIZE'
export type MetadataEnrichmentStatus = 'WAITING' | 'RUNNING' | 'FAILED' | 'STALE' | 'SUCCESS'
export type DictionaryUpdatePolicy = 'MARK_STALE' | 'AUTO_LOW_PRIORITY'

export interface MetadataEnrichmentBucket {
  min: number
  max: number | null
  label: string
}

export interface MetadataEnrichmentSettingsDto {
  aiEnabled: boolean
  aiAutoOnNew: boolean
  aiBaseUrl: string
  aiModel: string
  apiKeyConfigured: boolean
  aiTimeoutSeconds: number
  aiMaxRetries: number
  dictionaryUpdatePolicy: DictionaryUpdatePolicy
  pageSizeBuckets: MetadataEnrichmentBucket[]
  tagSizeBuckets: MetadataEnrichmentBucket[]
  baseDictionaryEntryCount: number
  overrideEntryCount: number
  dictionaryFingerprint: string
}

export interface MetadataEnrichmentSettingsUpdateDto {
  aiEnabled?: boolean
  aiAutoOnNew?: boolean
  aiBaseUrl?: string
  aiModel?: string
  aiApiKey?: string
  clearAiApiKey?: boolean
  aiTimeoutSeconds?: number
  aiMaxRetries?: number
  dictionaryUpdatePolicy?: DictionaryUpdatePolicy
  pageSizeBuckets?: MetadataEnrichmentBucket[]
  tagSizeBuckets?: MetadataEnrichmentBucket[]
}

export interface MetadataEnrichmentStatusCountDto {
  processor: MetadataEnrichmentProcessor
  status: MetadataEnrichmentStatus
  count: number
}

export interface MetadataEnrichmentStateDto {
  bookId: string
  bookName: string
  bookTitle: string
  seriesId: string
  libraryId: string
  processor: MetadataEnrichmentProcessor
  status: MetadataEnrichmentStatus
  revision: number
  resultRevision: number | null
  hasResult: boolean
  lastError: string | null
  startedDate: string | null
  completedDate: string | null
  lastModifiedDate: string
}

export interface MetadataEnrichmentRunRequestDto {
  processor: MetadataEnrichmentProcessor
  bookIds?: string[]
  status?: MetadataEnrichmentStatus
  libraryId?: string
}

export interface MetadataEnrichmentRunResultDto {
  accepted: number
}

export interface MetadataEnrichmentOverrideDto {
  k: string
  v: string
  t: string
  n?: any
}

export interface MetadataEnrichmentDictionaryResultDto {
  baseDictionaryEntryCount: number
  overrideEntryCount: number
  dictionaryFingerprint: string
  invalidatedBooks: number
}

export interface MetadataEnrichmentMissingTagDto {
  type: string
  value: string
  bookCount: number
}

export default class KomgaMetadataEnrichmentService {
  private http: AxiosInstance

  constructor(http: AxiosInstance) {
    this.http = http
  }

  async getSettings(): Promise<MetadataEnrichmentSettingsDto> {
    return (await this.http.get(API_METADATA_ENRICHMENT)).data
  }

  async updateSettings(settings: MetadataEnrichmentSettingsUpdateDto): Promise<void> {
    await this.http.patch(API_METADATA_ENRICHMENT, settings)
  }

  async getStats(): Promise<MetadataEnrichmentStatusCountDto[]> {
    return (await this.http.get(`${API_METADATA_ENRICHMENT}/stats`)).data
  }

  async getStates(params: {
    processor?: MetadataEnrichmentProcessor
    status?: MetadataEnrichmentStatus
    libraryId?: string
    page?: number
    size?: number
  }): Promise<Page<MetadataEnrichmentStateDto>> {
    return (await this.http.get(`${API_METADATA_ENRICHMENT}/states`, {params})).data
  }

  async requestRuns(request: MetadataEnrichmentRunRequestDto): Promise<MetadataEnrichmentRunResultDto> {
    return (await this.http.post(`${API_METADATA_ENRICHMENT}/runs`, request)).data
  }

  async replaceBaseDictionary(file: File): Promise<MetadataEnrichmentDictionaryResultDto> {
    const form = new FormData()
    form.append('file', file)
    return (await this.http.post(`${API_METADATA_ENRICHMENT}/dictionary/base`, form)).data
  }

  async getOverrides(): Promise<MetadataEnrichmentOverrideDto[]> {
    return (await this.http.get(`${API_METADATA_ENRICHMENT}/dictionary/overrides`)).data
  }

  async putOverride(override: MetadataEnrichmentOverrideDto): Promise<MetadataEnrichmentDictionaryResultDto> {
    return (await this.http.put(`${API_METADATA_ENRICHMENT}/dictionary/overrides`, override)).data
  }

  async deleteOverride(type: string, key: string): Promise<MetadataEnrichmentDictionaryResultDto> {
    return (await this.http.delete(`${API_METADATA_ENRICHMENT}/dictionary/overrides`, {params: {type, key}})).data
  }

  async getUntranslatedTags(params: {
    search?: string
    type?: string
    page?: number
    size?: number
  }): Promise<Page<MetadataEnrichmentMissingTagDto>> {
    return (await this.http.get(`${API_METADATA_ENRICHMENT}/untranslated-tags`, {params})).data
  }
}
