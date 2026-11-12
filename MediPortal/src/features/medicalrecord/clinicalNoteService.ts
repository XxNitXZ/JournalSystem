import { apiGet, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type { ClinicalNote, NewClinicalNoteRequest } from './types.ts'

/** The clinical notes of one registered patient, newest first (DOCTOR and STAFF). */
export function listPatientNotes(patientId: string, options?: ApiRequestOptions): Promise<ClinicalNote[]> {
  return apiGet<ClinicalNote[]>(`/api/patients/${encodeURIComponent(patientId)}/notes`, options)
}

/** The signed-in patient's own clinical notes, newest first (PATIENT). The record is chosen by MediCore. */
export function listOwnNotes(options?: ApiRequestOptions): Promise<ClinicalNote[]> {
  return apiGet<ClinicalNote[]>('/api/patients/me/notes', options)
}

/** Writes a note for the patient in the URL (DOCTOR and STAFF), with CSRF. Never retried automatically. */
export function createClinicalNote(patientId: string, request: NewClinicalNoteRequest,
  options?: ApiRequestOptions): Promise<ClinicalNote> {
  return apiPost<ClinicalNote>(`/api/patients/${encodeURIComponent(patientId)}/notes`, request, options)
}
