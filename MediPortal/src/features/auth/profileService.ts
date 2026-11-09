import { ApiError, apiGet, apiPatch, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type { Role } from './types.ts'

/**
 * The signed-in user's own account from MediCore. Names and personnummer are read-only after registration; e-mail
 * and phone number can be changed by the account holder.
 */
export interface AccountProfile {
  givenName: string | null
  familyName: string | null
  personnummer: string | null
  email: string
  phoneNumber: string | null
  role: Role
}

/** Body of PATCH /api/auth/profile: only contact fields; a missing field is left unchanged. */
export interface ContactUpdate {
  email?: string
  /** null removes the phone number. */
  phoneNumber?: string | null
  /** Required when the e-mail changes. */
  currentPassword?: string
}

export interface PasswordChange {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

/** The signed-in account; MediCore identifies it from the session. */
export function getAccountProfile(options?: ApiRequestOptions): Promise<AccountProfile> {
  return apiGet<AccountProfile>('/api/auth/profile', options)
}

/** Changes the e-mail and/or phone number of the signed-in account. */
export function updateAccountContact(update: ContactUpdate, options?: ApiRequestOptions): Promise<AccountProfile> {
  return apiPatch<AccountProfile>('/api/auth/profile', update, options)
}

/** Changes the password of the signed-in account; the session continues. */
export async function changePassword(change: PasswordChange, options?: ApiRequestOptions): Promise<void> {
  await apiPost<void>('/api/auth/password', change, options)
}

const codeMessages: Record<string, string> = {
  CURRENT_PASSWORD_INVALID: 'Your current password is not correct.',
  EMAIL_TAKEN: 'This email address is already used by another account.',
  EMAIL_INVALID: 'Enter a valid email address of at most 255 characters.',
  PHONE_INVALID: 'Enter a valid phone number with 6 to 15 digits, optionally starting with +.',
  PASSWORD_INVALID: 'Enter a new password of up to 72 characters. Some special characters count as more than one.',
  PASSWORD_MISMATCH: 'The new passwords must match.',
  FIELD_NOT_EDITABLE: 'Your name and personnummer cannot be changed.',
}

/** User feedback for a failed account change; never repeats the entered values. */
export function accountErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Unable to reach MediCore. Check your connection and try again.'
  }
  if (error.code && codeMessages[error.code]) return codeMessages[error.code]
  switch (error.status) {
    case 400:
      return 'Check the entered details and try again.'
    case 401:
      return 'Your session has ended. Sign in again to update your account.'
    case 403:
      return 'Your account could not be updated. Please sign in again.'
    default:
      return 'Your changes could not be saved. Please try again later.'
  }
}
