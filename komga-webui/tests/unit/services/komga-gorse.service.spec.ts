import {AxiosInstance} from 'axios'
import KomgaGorseService from '@/services/komga-gorse.service'

describe('KomgaGorseService', () => {
  const post = jest.fn()
  const service = new KomgaGorseService({post} as unknown as AxiosInstance)

  beforeEach(() => {
    jest.clearAllMocks()
  })

  it('posts connection test settings to the dedicated endpoint', async () => {
    const request = {apiUrl: 'http://gorse:8088', apiKey: 'secret'}
    const result = {
      ready: true,
      dataStoreConnected: true,
      cacheStoreConnected: true,
      apiAuthenticated: true,
    }
    post.mockResolvedValueOnce({data: result})

    await expect(service.testConnection(request)).resolves.toEqual(result)
    expect(post).toHaveBeenCalledWith('/api/v1/gorse/test-connection', request)
  })

  it('exposes the safe connection failure reason returned by Komga', async () => {
    post.mockRejectedValueOnce({response: {data: {message: 'Gorse API 密钥验证失败'}}})

    await expect(service.testConnection({apiUrl: 'http://gorse:8088', apiKey: 'wrong'}))
      .rejects.toThrow('Gorse API 密钥验证失败')
  })
})
