import {AxiosInstance} from 'axios'
import KomgaBooksService from '@/services/komga-books.service'

describe('Book sibling failures', () => {
  const get = jest.fn()
  const service = new KomgaBooksService({get} as unknown as AxiosInstance)

  it('distinguishes the end of a series from a server failure', async () => {
    get.mockRejectedValueOnce({response: {status: 404, data: {}}})
    await expect(service.getBookSiblingNext('book')).rejects.toMatchObject({status: 404})
    get.mockRejectedValueOnce({response: {status: 500, data: {message: 'Unavailable'}}})
    await expect(service.getBookSiblingNext('book')).rejects.toMatchObject({status: 500})
  })

  it('preserves network failures without assuming an HTTP response exists', async () => {
    get.mockRejectedValueOnce(new Error('offline'))
    await expect(service.getBookSiblingPrevious('book')).rejects.toMatchObject({status: undefined})
  })
})
