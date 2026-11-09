import { INVALID_PHONE_NUMBER, normalizePhoneNumber } from './phoneNumber.ts'
import { accountErrorMessage, type AccountProfile, type ContactUpdate, type PasswordChange } from './profileService.ts'
import type { UserDto } from './types.ts'

export interface ContactFields {
  email: string
  phoneNumber: string
  currentPassword: string
}

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/**
 * The request for a contact change, or the first problem. The e-mail is sent only when it changes (case is ignored,
 * as MediCore stores it in lower case) and then needs the current password; the phone number is always sent
 * (empty removes it). Messages never repeat the entered values.
 */
export function contactUpdate(fields: ContactFields, currentEmail: string):
  { ok: true; update: ContactUpdate } | { ok: false; error: string } {
  const email = fields.email.trim()
  if (email.length > 255 || !EMAIL.test(email)) {
    return { ok: false, error: 'Enter a valid email address of at most 255 characters.' }
  }
  const phone = normalizePhoneNumber(fields.phoneNumber)
  if (!phone.ok) return { ok: false, error: INVALID_PHONE_NUMBER }
  const update: ContactUpdate = { phoneNumber: phone.value }
  if (email.toLowerCase() !== currentEmail.toLowerCase()) {
    if (!fields.currentPassword) {
      return { ok: false, error: 'Enter your current password to change your email address.' }
    }
    update.email = email
    update.currentPassword = fields.currentPassword
  }
  return { ok: true, update }
}

export type SaveContactResult = { ok: true; profile: AccountProfile } | { ok: false; error: string }

/**
 * Validates and saves a contact change. After a successful save the signed-in user is updated at once with the new
 * e-mail (the session in MediCore follows the same account), through {@code accept}.
 */
export async function saveContact(fields: ContactFields, user: UserDto, deps: {
  update: (update: ContactUpdate) => Promise<AccountProfile>
  accept: (user: UserDto) => void
}): Promise<SaveContactResult> {
  const checked = contactUpdate(fields, user.email)
  if ('error' in checked) return { ok: false, error: checked.error }
  try {
    const profile = await deps.update(checked.update)
    if (profile.email !== user.email) deps.accept({ ...user, email: profile.email })
    return { ok: true, profile }
  } catch (failure: unknown) {
    return { ok: false, error: accountErrorMessage(failure) }
  }
}

/** The first problem with a password change, or null. Same rules as registration; passwords are never trimmed. */
export function validatePasswordChange(change: PasswordChange): string | null {
  if (!change.currentPassword) return 'Enter your current password.'
  if (!change.newPassword.trim() || new TextEncoder().encode(change.newPassword).length > 72) {
    return 'Enter a new password of up to 72 characters. Some special characters count as more than one.'
  }
  if (change.newPassword !== change.confirmPassword) return 'The new passwords must match.'
  return null
}
