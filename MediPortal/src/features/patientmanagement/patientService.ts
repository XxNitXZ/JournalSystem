import { apiGet, apiPatch, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type { CreatePatientRequest, PatientDto, PatientProfileDto, UpdatePatientRequest } from './types.ts'

/** Registers a new Patient in HAPI FHIR through MediCore (DOCTOR and STAFF only). Never retried automatically. */
export function registerPatient(request: CreatePatientRequest, options?: ApiRequestOptions): Promise<PatientDto> {
  return apiPost<PatientDto>('/api/patients', request, options)
}

/**
 * One Patient's profile, read by MediCore directly from HAPI FHIR, with the e-mail of a linked PATIENT account
 * (DOCTOR and STAFF only).
 */
export function getPatient(id: string, options?: ApiRequestOptions): Promise<PatientProfileDto> {
  return apiGet<PatientProfileDto>(`/api/patients/${encodeURIComponent(id)}`, options)
}

/**
 * Updates a patient's given names, family name and gender (DOCTOR and STAFF only). `version` is the version of
 * the profile that was edited; MediCore refuses the update (409) if the patient was changed in the meantime.
 * Never retried automatically.
 */
export function updatePatient(id: string, request: UpdatePatientRequest, version: string,
  options?: ApiRequestOptions): Promise<PatientProfileDto> {
  return apiPatch<PatientProfileDto>(`/api/patients/${encodeURIComponent(id)}`, request,
    { ...options, headers: { ...options?.headers, 'If-Match': `"${version}"` } })
}

/** The signed-in PATIENT's own medical record (PATIENT only). The record is chosen by MediCore, never by the client. */
export function getOwnPatientRecord(options?: ApiRequestOptions): Promise<PatientProfileDto> {
  return apiGet<PatientProfileDto>('/api/patients/me', options)
}

/**
 * Searches the patients known to MediCore by name or exact personnummer. The query is sent in the request body,
 * never in the URL, so that personnummer do not end up in URLs or browser history.
 */
export function searchPatients(query: string, options?: ApiRequestOptions): Promise<PatientDto[]> {
  return apiPost<PatientDto[]>('/api/patients/search', { query }, options)
}
