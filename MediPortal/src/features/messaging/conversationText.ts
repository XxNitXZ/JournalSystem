import { ApiError } from '../../services/apiClient.ts'

export function conversationError(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Your session has expired. Please sign in again.'
    if (error.status === 403) return 'You do not have access to these messages.'
    if (error.status === 400 || error.status === 404) return 'This conversation is not available.'
  }
  return 'Messages could not be loaded. Please reload the page to try again.'
}

export function messageTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? 'Time unavailable' : date.toLocaleString()
}
