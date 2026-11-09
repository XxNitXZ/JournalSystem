import { isFuturePersonnummer, localToday, normalizePersonnummer } from '../patientmanagement/personnummer.ts'
import { roles } from './types.ts'

const MAX_NAME_LENGTH = 100

/** Same rule as MediCore: nonblank after trimming, at most 100 characters, no control characters. */
function validName(value: string): boolean {
  const name = value.trim()
  // eslint-disable-next-line no-control-regex
  return name.length > 0 && [...name].length <= MAX_NAME_LENGTH && !/[\u0000-\u001f\u007f-\u009f]/.test(name)
}

/** The first problem with the entered first and last name, or null. */
export function validateAccountNames(givenName: string, familyName: string): string | null {
  if (!validName(givenName)) {
    return `Enter your first name (at most ${MAX_NAME_LENGTH} characters).`
  }
  if (!validName(familyName)) {
    return `Enter your last name (at most ${MAX_NAME_LENGTH} characters).`
  }
  return null
}

export function validateRegistration(email: string, password: string, confirmation: string, role: string): string | null {
  if (email.length > 255 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return 'Enter a valid email address of at most 255 characters.'
  }
  if (!password.trim() || new TextEncoder().encode(password).length > 72) {
    return 'Enter a password of up to 72 characters. Some special characters count as more than one.'
  }
  if (!confirmation || password !== confirmation) {
    return 'Passwords must match.'
  }
  if (!roles.some((value) => value === role)) {
    return 'Select a role.'
  }
  return null
}

/**
 * The first problem with the entered personnummer, or null. Required for every new account; MediCore uses it to
 * find the account holder's medical record. Messages never repeat the entered value.
 */
export function validateAccountPersonnummer(value: string, today = localToday()): string | null {
  if (!value.trim()) return 'Enter your personnummer.'
  if (isFuturePersonnummer(value, today)) return 'The date of birth in the personnummer cannot be in the future.'
  if (!normalizePersonnummer(value, today)) {
    return 'Enter a valid personnummer: 12 digits (YYYYMMDDNNNN or YYYYMMDD-NNNN) starting with a real date of birth.'
  }
  return null
}
