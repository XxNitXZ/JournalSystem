import { apiGet, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type { Diagnosis, NewDiagnosisRequest } from './types.ts'

/** The diagnoses of one registered patient, newest first (DOCTOR and STAFF). */
export function listPatientDiagnoses(patientId: string, options?: ApiRequestOptions): Promise<Diagnosis[]> {
  return apiGet<Diagnosis[]>(`/api/patients/${encodeURIComponent(patientId)}/conditions`, options)
}

/** The signed-in patient's own diagnoses, newest first (PATIENT). The record is chosen by MediCore. */
export function listOwnDiagnoses(options?: ApiRequestOptions): Promise<Diagnosis[]> {
  return apiGet<Diagnosis[]>('/api/patients/me/conditions', options)
}

/** Records a diagnosis for the patient in the URL (DOCTOR and STAFF), with CSRF. Never retried automatically. */
export function createDiagnosis(patientId: string, request: NewDiagnosisRequest,
  options?: ApiRequestOptions): Promise<Diagnosis> {
  return apiPost<Diagnosis>(`/api/patients/${encodeURIComponent(patientId)}/conditions`, request, options)
}
