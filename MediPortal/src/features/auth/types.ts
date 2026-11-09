export const roles = ['PATIENT', 'DOCTOR', 'STAFF'] as const
export type Role = (typeof roles)[number]

export interface RegistrationRequest {
  email: string
  password: string
  role: Role
  /** First name; required for every new account. */
  givenName: string
  /** Last name; required for every new account. */
  familyName: string
  /** Swedish personnummer, 12 digits (YYYYMMDDNNNN); required for every new account and never returned. */
  personnummer: string
  /** Optional phone number (normalized), or null. Never returned in account responses other than the profile. */
  phoneNumber?: string | null
}

export interface UserDto {
  id: number
  email: string
  role: Role
  /** First name; null for accounts created before names were stored. */
  givenName?: string | null
  /** Last name; null for accounts created before names were stored. */
  familyName?: string | null
}

export type RegistrationResponse = UserDto

export interface LoginRequest {
  email: string
  password: string
}
