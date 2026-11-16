import type { PatientDto } from './types.ts'

export const PERSONNUMMER_SYSTEM = 'http://electronichealth.se/identifier/personnummer'
export const NOT_RECORDED = 'Not recorded'
export const NOT_AVAILABLE = 'Not available'

const genderNames: Record<string, string> = {
  female: 'Female',
  male: 'Male',
  other: 'Other',
  unknown: 'Unknown',
}

/** The official name if there is one, otherwise the first name; "Not recorded" if the Patient has none. */
export function displayName(patient: PatientDto): string {
  const name = patient.names.find((candidate) => candidate.use === 'official') ?? patient.names[0]
  if (!name) return NOT_RECORDED
  const parts = [...name.given, name.family].filter((part): part is string => Boolean(part && part.trim()))
  if (parts.length > 0) return parts.join(' ')
  return name.text?.trim() || NOT_RECORDED
}

/** The Swedish personnummer as YYYYMMDD-NNNN, or "Not recorded". */
export function displayPersonnummer(patient: PatientDto): string {
  const value = patient.identifiers.find((identifier) => identifier.system === PERSONNUMMER_SYSTEM)?.value
  if (!value) return NOT_RECORDED
  return /^\d{12}$/.test(value) ? `${value.slice(0, 8)}-${value.slice(8)}` : value
}

export function displayGender(patient: PatientDto): string {
  if (!patient.gender) return NOT_RECORDED
  return genderNames[patient.gender] ?? patient.gender
}

/** The e-mail of the linked PATIENT account, or "Not available" when no account is linked. */
export function displayEmail(patient: { email?: string | null }): string {
  return patient.email?.trim() || NOT_AVAILABLE
}

/** The phone number of the linked PATIENT account, or "Not available". */
export function displayPhone(patient: { phoneNumber?: string | null }): string {
  return patient.phoneNumber?.trim() || NOT_AVAILABLE
}

export function displayBirthDate(patient: PatientDto): string {
  return patient.birthDate || NOT_RECORDED
}
