import { ApiError } from '../../services/apiClient.ts'

export function loginErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Invalid email or password.'
    if (error.status === 400) return 'Check your email and password.'
    if (error.status === 403) return 'Your security token expired. Please try again.'
    return 'Unable to sign in. Please try again later.'
  }
  return 'Unable to reach the server. Check your connection and try again.'
}
