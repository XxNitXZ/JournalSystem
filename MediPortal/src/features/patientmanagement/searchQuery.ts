import { isValidPersonnummer } from './personnummer.ts'

/** Same rules as MediCore: an exact personnummer (12 digits, optionally YYYYMMDD-NNNN) or 1–5 name parts. */
export function validateSearchQuery(query: string): string | null {
  const trimmed = query.trim()
  if (/^\d{8}-?\d{4}$/.test(trimmed)) {
    return isValidPersonnummer(trimmed.replace('-', '')) ? null : 'Enter a valid 12-digit personnummer.'
  }
  const parts = trimmed.split(/\s+/).filter(Boolean)
  const letters = [...trimmed].filter((character) => /\p{L}/u.test(character)).length
  if (!trimmed || trimmed.length > 100 || parts.length > 5 || letters < 2
    || !parts.every((part) => /^[\p{L}\p{M}'’-]+$/u.test(part))) {
    return 'Enter at least two letters of a name, or a complete personnummer.'
  }
  return null
}
