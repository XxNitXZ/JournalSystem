/**
 * The optional account phone number, Swedish or international; the same rules as MediCore, which remains
 * authoritative. Digits with an optional leading "+", and spaces, "-", "." or parentheses as separators (at most 32
 * characters). Normalizing only removes the separators: digits are never changed and "00" is never turned into "+".
 * The result is an optional "+" and 6 to 15 digits.
 */

export type PhoneNumberResult = { ok: true; value: string | null } | { ok: false }

/** The normalized number, null for an empty value (no phone number), or not ok for an invalid number. */
export function normalizePhoneNumber(value: string | null | undefined): PhoneNumberResult {
  const stripped = (value ?? '').trim()
  if (!stripped) return { ok: true, value: null }
  if (stripped.length > 32 || !/^\+?[0-9 ().-]+$/.test(stripped)) return { ok: false }
  const normalized = stripped.replace(/[ ().-]/g, '')
  if (!/^\+?[0-9]{6,15}$/.test(normalized)) return { ok: false }
  return { ok: true, value: normalized }
}

export const PHONE_NUMBER_HINT = 'Optional. Swedish or international number, for example 070-123 45 67 or +46 70 123 45 67.'
export const INVALID_PHONE_NUMBER = 'Enter a valid phone number with 6 to 15 digits, optionally starting with +.'
