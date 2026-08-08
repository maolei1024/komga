import {DedupImageJob, DedupImageQueue, DedupImagePriority} from '@/functions/dedup-image-queue'

describe('DedupImageQueue', () => {
  it('starts at most the configured number of jobs and ignores duplicate releases', () => {
    const queue = new DedupImageQueue(4)
    const started: string[] = []
    const releases: Array<() => void> = []

    for (let index = 1; index <= 5; index++) queue.enqueue(job(`${index}`, 'normal', started, releases))

    expect(started).toEqual(['1', '2', '3', '4'])
    releases[0]()
    expect(started).toEqual(['1', '2', '3', '4', '5'])
    releases[0]()
    expect(started).toHaveLength(5)
  })

  it('starts waiting high priority jobs before normal jobs while preserving FIFO order', () => {
    const queue = new DedupImageQueue(1)
    const started: string[] = []
    const releases: Array<() => void> = []

    queue.enqueue(job('active', 'normal', started, releases))
    queue.enqueue(job('normal-1', 'normal', started, releases))
    queue.enqueue(job('high-1', 'high', started, releases))
    queue.enqueue(job('high-2', 'high', started, releases))
    queue.enqueue(job('normal-2', 'normal', started, releases))

    releases[0]()
    releases[1]()
    releases[2]()
    releases[3]()

    expect(started).toEqual(['active', 'high-1', 'high-2', 'normal-1', 'normal-2'])
  })

  it('skips a cancelled waiting job', () => {
    const queue = new DedupImageQueue(1)
    const started: string[] = []
    const releases: Array<() => void> = []
    const cancelled = job('cancelled', 'high', started, releases)

    queue.enqueue(job('active', 'normal', started, releases))
    queue.enqueue(cancelled)
    queue.enqueue(job('next', 'normal', started, releases))
    cancelled.cancelled = true
    releases[0]()

    expect(started).toEqual(['active', 'next'])
  })

  it('rejects invalid concurrency limits', () => {
    expect(() => new DedupImageQueue(0)).toThrow('maxActive must be a positive integer')
  })
})

function job(
  name: string,
  priority: DedupImagePriority,
  started: string[],
  releases: Array<() => void>,
): DedupImageJob {
  return {
    priority,
    cancelled: false,
    start: release => {
      started.push(name)
      releases.push(release)
    },
  }
}
