import { ApiError, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import { isValidPersonnummer } from './personnummer.ts'

/** How to search the FHIR server for an existing patient. */
export const importSearchModes = ['NAME', 'PERSONNUMMER', 'ID'] as const
export type ImportSearchMode = (typeof importSearchModes)[number]

export const importSearchModeLabels: Record<ImportSearchMode, string> = {
  NAME: 'Name',
  PERSONNUMMER: 'Personnummer',
  ID: 'Record ID',
}

/** An existing patient on the FHIR server, as returned by MediCore (readable values only). */
export interface PatientCandidate {
  /** Logical record ID: used for the import and the profile link, not shown in ordinary cards. */
  id: string
  name: string | null
  personnummer: string | null
  gender: string | null
  birthDate: string | null
  /** Already part of MediCore (registered, imported or linked to an account). */
  registered: boolean
  /** Has a name and a birth date or an identifier, so it can be imported. */
  importable: boolean
  /** Has a valid personnummer, so a patient's journal access request can be matched to it. */
  journalMatchable: boolean
  /**
   * VALID (personnummer is then the canonical 12 digits, whether stored as YYYYMMDDNNNN or YYYYMMDD-NNNN), AMBIGUOUS
   * (several different values), INVALID (shown as stored) or MISSING.
   */
  personnummerStatus?: PersonnummerStatus
}

export type PersonnummerStatus = 'VALID' | 'AMBIGUOUS' | 'INVALID' | 'MISSING'

/**
 * Why a patient that can be imported cannot be matched with a patient account, or null when it can. The identifier
 * system is never shown.
 */
export function personnummerNote(candidate: PatientCandidate): string | null {
  if (candidate.journalMatchable) return null
  const clinicalOnly = 'can be used clinically, but cannot be matched with a patient account.'
  switch (candidate.personnummerStatus ?? (candidate.personnummer ? 'INVALID' : 'MISSING')) {
    case 'AMBIGUOUS':
      return `Several different personnummer are recorded: ${clinicalOnly}`
    case 'INVALID':
      return `No valid personnummer: ${clinicalOnly}`
    default:
      return `No personnummer: ${clinicalOnly}`
  }
}

export interface PatientDiscovery {
  patients: PatientCandidate[]
  /** The server has more matches than MediCore fetched (at most 100); refine the search. */
  truncated: boolean
}

/** Searches the patient records server for existing patients (DOCTOR and STAFF). The value is never in a URL. */
export function discoverPatients(mode: ImportSearchMode, value: string, options?: ApiRequestOptions): Promise<PatientDiscovery> {
  return apiPost<PatientDiscovery>('/api/patients/import/search', { mode, value }, options)
}

/** Adds an existing patient to MediCore by reference; nothing is created or changed on the records server. */
export function importPatient(fhirPatientId: string, options?: ApiRequestOptions): Promise<PatientCandidate> {
  return apiPost<PatientCandidate>('/api/patients/import', { fhirPatientId }, options)
}

/** The value to send, or the first problem with the entered search (same rules as MediCore). */
export function importSearchRequest(mode: string, value: string):
  { ok: true; mode: ImportSearchMode; value: string } | { ok: false; error: string } {
  const trimmed = value.trim()
  if (mode === 'PERSONNUMMER') {
    const digits = /^\d{8}-\d{4}$/.test(trimmed) ? trimmed.replace('-', '') : trimmed
    if (!isValidPersonnummer(digits)) {
      return { ok: false, error: 'Enter a valid personnummer (YYYYMMDDNNNN or YYYYMMDD-NNNN).' }
    }
    return { ok: true, mode, value: digits }
  }
  if (mode === 'ID') {
    if (!/^[A-Za-z0-9.-]{1,64}$/.test(trimmed)) return { ok: false, error: 'Enter a valid record ID.' }
    return { ok: true, mode, value: trimmed }
  }
  if (mode === 'NAME') {
    const parts = trimmed.split(/\s+/).filter(Boolean)
    const letters = [...trimmed].filter((character) => /\p{L}/u.test(character)).length
    if (trimmed.length > 100 || parts.length === 0 || parts.length > 5 || letters < 2
      || !parts.every((part) => /^[\p{L}\p{M}'’-]+$/u.test(part))) {
      return { ok: false, error: 'Enter at least two letters of a name (letters, hyphens and apostrophes only).' }
    }
    return { ok: true, mode, value: trimmed }
  }
  return { ok: false, error: 'Select how to search.' }
}

export const IMPORT_PAGE_SIZE = 10

/** One page of results (1-based) and the page count. */
export function pageOf<T>(items: T[], page: number, size = IMPORT_PAGE_SIZE): { items: T[]; page: number; pages: number } {
  const pages = Math.max(1, Math.ceil(items.length / size))
  const current = Math.min(Math.max(1, page), pages)
  return { items: items.slice((current - 1) * size, current * size), page: current, pages }
}

const genderNames: Record<string, string> = { female: 'Female', male: 'Male', other: 'Other', unknown: 'Unknown' }

export function formatCandidateGender(gender: string | null): string {
  return gender ? genderNames[gender] ?? gender : 'Not recorded'
}

/** A 12-digit personnummer as YYYYMMDD-NNNN; other values as stored; "Several recorded"; "No personnummer". */
export function formatCandidatePersonnummer(value: string | null, status?: PersonnummerStatus): string {
  if (status === 'AMBIGUOUS') return 'Several recorded'
  if (!value) return 'No personnummer'
  return /^\d{12}$/.test(value) ? `${value.slice(0, 8)}-${value.slice(8)}` : value
}

/** Feedback for a failed search; never repeats the search value. */
export function importSearchErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Unable to reach MediCore. Check your connection and try again.'
  if (error.status === 400) return 'Check the search and try again.'
  if (error.status === 401) return 'Your session has ended. Sign in again to search.'
  if (error.status === 403) return 'Only doctor and staff accounts can import patients.'
  if (error.status === 502 || error.status === 503) {
    return 'The patient records server is temporarily unavailable. Please try again later.'
  }
  return 'The search could not be completed. Please try again later.'
}

/** Feedback for a failed import. */
export function importErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Unable to reach MediCore. Check your connection and try again.'
  if (error.status === 409 && error.code === 'ALREADY_REGISTERED') return 'This patient is already in MediCore.'
  if (error.status === 422 && error.code === 'PATIENT_NOT_IMPORTABLE') {
    return 'This patient record has too little information to be identified and cannot be imported.'
  }
  if (error.status === 404) return 'This patient no longer exists on the records server.'
  if (error.status === 403) return 'Only doctor and staff accounts can import patients.'
  if (error.status === 401) return 'Your session has ended. Sign in again to import.'
  if (error.status === 502 || error.status === 503) {
    return 'The patient records server is temporarily unavailable. Please try again later.'
  }
  return 'The patient could not be imported. Please try again later.'
}
