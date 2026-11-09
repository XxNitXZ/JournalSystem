import type { UserDto } from './types.ts'

/** The stored first name, or null. Names are never derived from the e-mail address. */
export function accountFirstName(user: UserDto): string | null {
  return user.givenName?.trim() || null
}

/** "First Last" when both names are stored, otherwise null (accounts created before names were required). */
export function accountFullName(user: UserDto): string | null {
  const given = user.givenName?.trim()
  const family = user.familyName?.trim()
  return given && family ? `${given} ${family}` : null
}
