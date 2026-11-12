import { apiGet, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type { NewObservationRequest, PatientObservation, ObservationSearchResult } from './types.ts'
export function listPatientObservations(patientId: string, options?: ApiRequestOptions): Promise<ObservationSearchResult> {
  return apiGet(`/api/patients/${encodeURIComponent(patientId)}/observations`, options)
}
export function listOwnObservations(options?: ApiRequestOptions): Promise<ObservationSearchResult> {
  return apiGet('/api/patients/me/observations', options)
}
export function createObservation(patientId: string, request: NewObservationRequest, options?: ApiRequestOptions): Promise<PatientObservation> {
  return apiPost(`/api/patients/${encodeURIComponent(patientId)}/observations`, request, options)
}
