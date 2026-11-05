import { ApiError } from '../../services/apiClient.ts'

export function registrationErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 409) {
      return 'This email address is already registered.'
    }
    if (error.status === 400) {
      return 'Registration data was rejected. Check your email, password, and role.'
    }
    return 'Registration failed. Please try again later.'
  }
  return 'Unable to reach the server. Check your connection and try again.'
}
