export class ReaderProgressWriter {
  private pending = new Map<string, number>()
  private timer: ReturnType<typeof setTimeout> | undefined
  private running: Promise<void> | undefined

  constructor(
    private readonly save: (bookId: string, page: number) => Promise<void>,
    private readonly onError: () => void,
  ) {}

  queue(bookId: string, page: number) {
    this.pending.set(bookId, page)
    if (this.timer) clearTimeout(this.timer)
    this.timer = setTimeout(() => {
      this.flush().catch(this.onError)
    }, 50)
  }

  async flush(): Promise<void> {
    if (this.timer) clearTimeout(this.timer)
    this.timer = undefined
    if (this.running) {
      await this.running
      return this.flush()
    }
    this.running = this.drain()
    try {
      await this.running
    } finally {
      this.running = undefined
    }
  }

  private async drain(): Promise<void> {
    while (this.pending.size) {
      const [bookId, page] = this.pending.entries().next().value as [string, number]
      this.pending.delete(bookId)
      try {
        await this.save(bookId, page)
      } catch (error) {
        if (!this.pending.has(bookId)) this.pending.set(bookId, page)
        throw error
      }
    }
  }
}
