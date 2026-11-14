export function isReaderAtBottom(element: HTMLElement = document.documentElement): boolean {
  const scrolling = document.scrollingElement || document.documentElement
  const bottom = element.getBoundingClientRect().bottom
  return bottom <= window.innerHeight + 2 && scrolling.scrollTop + window.innerHeight >= scrolling.scrollHeight - 2
}

export function isTyping(event: KeyboardEvent): boolean {
  const target = event.target as HTMLElement | null
  return !!target?.closest('input, textarea, select, [contenteditable="true"]')
}
