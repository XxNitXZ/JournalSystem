import { ApiError } from '../../services/apiClient.ts'
import type { MessageRecipient, MessageSentResponse, SendMessageRequest } from './types.ts'

export function validateMessage(recipient: string, content: string, recipients: MessageRecipient[]): string | null {
  if (!recipients.some((item) => String(item.id) === recipient)) return 'Choose a recipient.'
  if (!content.trim()) return 'Enter a message.'
  if ([...content].length > 5000) return 'Your message must be at most 5,000 characters.'
  return null
}

export function messageErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 400) return 'Check the recipient and message, then try again.'
    if (error.status === 401) return 'Your session has expired. Please sign in again.'
    if (error.status === 403) return 'You cannot perform this action. Try signing in again.'
    if (error.status === 404) return 'The recipient is no longer available. Reload the page to choose another.'
  }
  return 'Unable to confirm delivery. Your draft is kept. Check before sending again to avoid a duplicate.'
}

export function recipientLoadError(error: unknown): string {
  if (error instanceof ApiError && (error.status === 401 || error.status === 403)) return messageErrorMessage(error)
  return 'Recipients could not be loaded. Please reload the page to try again.'
}

/** A synchronous guard also blocks repeated submits before React renders the pending state. */
export async function submitMessage(recipient: string, content: string, recipients: MessageRecipient[],
  guard: { current: boolean }, send: (request: SendMessageRequest) => Promise<MessageSentResponse>):
  Promise<{ status: 'sent' } | { status: 'error'; message: string } | { status: 'pending' }> {
  if (guard.current) return { status: 'pending' }
  const error = validateMessage(recipient, content, recipients)
  if (error) return { status: 'error', message: error }
  guard.current = true
  try {
    await send({ recipientId: Number(recipient), content })
    return { status: 'sent' }
  } catch (failure: unknown) {
    return { status: 'error', message: messageErrorMessage(failure) }
  } finally {
    guard.current = false
  }
}
