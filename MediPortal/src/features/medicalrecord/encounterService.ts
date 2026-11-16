import { apiGet, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type { EncounterListResult, EncounterSummary, NewEncounterRequest, OrganizationOption, PractitionerOption } from './types.ts'

/** The visits of one registered patient, newest first (DOCTOR and STAFF). */
export function listPatientEncounters(patientId: string, options?: ApiRequestOptions): Promise<EncounterListResult> {
  return apiGet<EncounterListResult>(`/api/patients/${encodeURIComponent(patientId)}/encounters`, options)
}

/** The signed-in patient's own visits, newest first (PATIENT). The record is chosen by MediCore, never by an ID. */
export function listOwnEncounters(options?: ApiRequestOptions): Promise<EncounterListResult> {
  return apiGet<EncounterListResult>('/api/patients/me/encounters', options)
}

/** Documents a visit for the patient in the URL (DOCTOR and STAFF). Never retried automatically. */
export function createEncounter(patientId: string, request: NewEncounterRequest,
  options?: ApiRequestOptions): Promise<EncounterSummary> {
  return apiPost<EncounterSummary>(`/api/patients/${encodeURIComponent(patientId)}/encounters`, request, options)
}

/** The practitioners that can be chosen for a visit (DOCTOR and STAFF). */
export function listPractitioners(options?: ApiRequestOptions): Promise<PractitionerOption[]> {
  return apiGet<PractitionerOption[]>('/api/practitioners', options)
}

/** Optional healthcare providers; organizations are not necessarily hospitals. */
export function listOrganizations(options?: ApiRequestOptions): Promise<OrganizationOption[]> {
  return apiGet<OrganizationOption[]>('/api/organizations', options)
}
