/**
 * Swedish personal identity number (personnummer), 12 digits: YYYYMMDDXXXX.
 * The first eight digits must be a real date of birth. The last four digits are not checked: like MediCore, which
 * remains authoritative, MediPortal does not validate the Luhn check digit, so fictional numbers can be used.
 */

/** The date of birth (YYYY-MM-DD) of a valid personnummer, otherwise null. */
export function personnummerBirthDate(value: string): string | null {
  if (!/^[0-9]{12}$/.test(value)) return null
  const year = Number(value.slice(0, 4))
  const month = Number(value.slice(4, 6))
  const day = Number(value.slice(6, 8))
  const date = new Date(Date.UTC(2000, month - 1, day))
  date.setUTCFullYear(year)
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null
  return `${value.slice(0, 4)}-${value.slice(4, 6)}-${value.slice(6, 8)}`
}

export function isValidPersonnummer(value: string): boolean {
  return personnummerBirthDate(value) !== null
}

/** Today's local date as YYYY-MM-DD. */
export function localToday(now = new Date()): string {
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

/**
 * The 12-digit form of YYYYMMDDNNNC or YYYYMMDD-NNNC (surrounding whitespace ignored), or null if the value is
 * not a valid personnummer or its date of birth is after today (YYYY-MM-DD). Same rule as MediCore.
 */
export function normalizePersonnummer(value: string, today = localToday()): string | null {
  let stripped = value.trim()
  if (/^\d{8}-\d{4}$/.test(stripped)) stripped = stripped.replace('-', '')
  const birthDate = personnummerBirthDate(stripped)
  if (!birthDate || birthDate > today) return null
  return stripped
}

/** True if the value has a valid format and date, but its date of birth is after today. */
export function isFuturePersonnummer(value: string, today = localToday()): boolean {
  let stripped = value.trim()
  if (/^\d{8}-\d{4}$/.test(stripped)) stripped = stripped.replace('-', '')
  const birthDate = personnummerBirthDate(stripped)
  return birthDate !== null && birthDate > today
}
