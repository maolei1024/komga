import {AxiosInstance} from 'axios'
import KomgaSeriesService from '@/services/komga-series.service'

describe('KomgaSeriesService', () => {
  const get = jest.fn()
  const service = new KomgaSeriesService({get} as unknown as AxiosInstance)

  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('retrieves Gorse recommended series with the requested page', async () => {
    const page = {
      content: [{id: 'series-1'}],
      number: 2,
      size: 6,
      totalElements: 12,
      totalPages: 2,
      last: true,
    }
    get.mockResolvedValueOnce({data: page})

    await expect(service.getRecommendedSeries({page: 2, size: 6})).resolves.toEqual(page)
    expect(get).toHaveBeenCalledWith('/api/v1/series/recommended', expect.objectContaining({
      params: {page: 2, size: 6},
      paramsSerializer: expect.any(Function),
    }))
  })
})
