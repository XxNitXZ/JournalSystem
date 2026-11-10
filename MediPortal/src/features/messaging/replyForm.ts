import { messageErrorMessage, validateMessageContent } from './messageForm.ts'

/** Send once; refresh failures must never be reported as failed sends or restore a sent draft. */
export async function submitReply(content: string, guard: { current: boolean }, signal: AbortSignal, deps: {
  send: (content: string) => Promise<unknown>
  confirmed: () => void
  refresh: () => Promise<void>
}): Promise<{ status: 'ignored' | 'sent' } | { status: 'error' | 'refreshError'; message: string }> {
  if (guard.current || signal.aborted) return { status: 'ignored' }
  const error = validateMessageContent(content)
  if (error) return { status: 'error', message: error }
  guard.current = true
  try {
    try {
      await deps.send(content)
    } catch (failure: unknown) {
      return signal.aborted ? { status: 'ignored' } : { status: 'error', message: messageErrorMessage(failure) }
    }
    if (signal.aborted) return { status: 'ignored' }
    deps.confirmed()
    try {
      await deps.refresh()
    } catch {
      return signal.aborted ? { status: 'ignored' } : { status: 'refreshError',
        message: 'Your reply was sent, but the conversation could not be refreshed. Reload the page; do not resend.' }
    }
    return signal.aborted ? { status: 'ignored' } : { status: 'sent' }
  } finally { guard.current = false }
}
