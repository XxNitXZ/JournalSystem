export const genders = ['FEMALE', 'MALE', 'OTHER', 'UNKNOWN'] as const
export type Gender = (typeof genders)[number]

export const genderLabels: Record<Gender, string> = {
  FEMALE: 'Female',
  MALE: 'Male',
  OTHER: 'Other',
  UNKNOWN: 'Unknown',
}

/** Body of POST /api/patients. The date of birth is derived from the personnummer by MediCore when omitted. */
export interface CreatePatientRequest {
  personnummer: string
  family: string
  given: string[]
  gender: Gender
  /** ISO date (YYYY-MM-DD); optional, must match the personnummer if given. */
  birthDate?: string
}

export interface PatientIdentifier {
  system: string | null
  value: string | null
}

export interface PatientName {
  use: string | null
  text: string | null
  family: string | null
  given: string[]
}

/** The registered Patient as returned by MediCore (data stored in HAPI FHIR). */
export interface PatientDto {
  id: string
  identifiers: PatientIdentifier[]
  names: PatientName[]
  gender: string | null
  birthDate: string | null
}

/**
 * A Patient profile: the FHIR Patient plus the e-mail of the PATIENT account linked to it (null when no account
 * is linked). Returned by GET /api/patients/{id} (DOCTOR/STAFF) and GET /api/patients/me (PATIENT).
 */
export interface PatientProfileDto extends PatientDto {
  /** E-mail of the linked PATIENT account, from MediCore's accounts (never from the patient record); null if none. */
  email: string | null
  /** Phone number of the linked PATIENT account, from MediCore's accounts; null if none. */
  phoneNumber?: string | null
  /**
   * The FHIR version of the record as read, for DOCTOR/STAFF profiles. Sent back as If-Match when the patient is
   * updated, so that changes by someone else are never overwritten. Never shown.
   */
  version?: string | null
}

/**
 * Body of PATCH /api/patients/{id}. Only the gender can change; names, personnummer, birth date and the record ID
 * are immutable and MediCore rejects any other field.
 */
export interface UpdatePatientRequest {
  gender: Gender
}
