export type DedupImagePriority = 'normal' | 'high'

export interface DedupImageJob {
  priority: DedupImagePriority
  cancelled: boolean
  start: (release: () => void) => void
}

export class DedupImageQueue {
  private active = 0
  private readonly waiting: DedupImageJob[] = []

  constructor(private readonly maxActive: number) {
    if (!Number.isInteger(maxActive) || maxActive < 1) throw new Error('maxActive must be a positive integer')
  }

  enqueue(job: DedupImageJob) {
    this.waiting.push(job)
    this.pump()
  }

  private pump() {
    while (this.active < this.maxActive && this.waiting.length) {
      const highPriorityIndex = this.waiting.findIndex(job => job.priority === 'high')
      const [job] = this.waiting.splice(highPriorityIndex >= 0 ? highPriorityIndex : 0, 1)
      if (job.cancelled) continue

      this.active++
      let released = false
      job.start(() => {
        if (released) return
        released = true
        this.active--
        this.pump()
      })
    }
  }
}

export const dedupImageQueue = new DedupImageQueue(4)
