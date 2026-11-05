import { roles } from './types.ts'

export function validateRegistration(email: string, password: string, confirmation: string, role: string): string | null {
  if (email.length > 255 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return 'Enter a valid email address of at most 255 characters.'
  }
  if (!password.trim() || new TextEncoder().encode(password).length > 72) {
    return 'Enter a nonblank password of at most 72 UTF-8 bytes.'
  }
  if (!confirmation || password !== confirmation) {
    return 'Passwords must match.'
  }
  if (!roles.some((value) => value === role)) {
    return 'Select a role.'
  }
  return null
}
