/**
 * The signed-in PATIENT's journal access, as returned by GET /api/journal-access/me and
 * POST /api/journal-access/me/request. NONE: the patient has not asked for access yet.
 */
export const patientJournalStatuses = ['NONE', 'AWAITING_RECORD', 'PENDING', 'APPROVED', 'REJECTED'] as const
export type PatientJournalStatus = (typeof patientJournalStatuses)[number]

/** AWAITING_RECORD: requested, but no registered patient record matches yet; it cannot be decided. */
export type JournalAccessRequestStatus = 'AWAITING_RECORD' | 'PENDING' | 'APPROVED' | 'REJECTED'

/** One request in the DOCTOR's list: the account's own data, never a FHIR ID. */
export interface JournalAccessRequestSummary {
  id: number
  givenName: string | null
  familyName: string | null
  personnummer: string | null
  email: string
  status: JournalAccessRequestStatus
  createdAt: string
  reviewedAt: string | null
}

export type RecordStatus = 'AVAILABLE' | 'NOT_FOUND' | 'UNAVAILABLE' | 'NOT_REGISTERED'

/** One request with the PostgreSQL account next to the matched FHIR Patient record. */
export interface JournalAccessRequestDetail {
  id: number
  status: JournalAccessRequestStatus
  createdAt: string
  reviewedAt: string | null
  account: {
    givenName: string | null
    familyName: string | null
    personnummer: string | null
    email: string
  }
  /** Null when the record could not be read (see recordStatus). */
  record: {
    givenNames: string[]
    familyName: string | null
    personnummer: string | null
    gender: string | null
    birthDate: string | null
  } | null
  recordStatus: RecordStatus
  personnummerMatches: boolean
}
