import { ApiError } from '../../services/apiClient.ts'
import type { Role } from '../auth/types.ts'
import { genders, type Gender, type PatientProfileDto, type UpdatePatientRequest } from './types.ts'

/** The value in the Edit Patient form, as entered. Only the gender can be edited. */
export interface PatientUpdateFields {
  gender: string
}

/** Only doctors and staff may edit a patient; patients never. MediCore enforces this as well. */
export function canEditPatients(role: Role | undefined | null): boolean {
  return role === 'DOCTOR' || role === 'STAFF'
}

/** The editable value of a profile. An unrecorded gender is left empty so that it must be chosen. */
export function editableFields(patient: PatientProfileDto): PatientUpdateFields {
  const gender = (patient.gender ?? '').toUpperCase()
  return { gender: genders.some((value) => value === gender) ? gender : '' }
}

/** The first problem with the entered value, or null. */
export function validatePatientUpdate(fields: PatientUpdateFields): string | null {
  return genders.some((gender) => gender === fields.gender) ? null : 'Select a gender.'
}

/** The request body: only the gender. Never names, personnummer, birth date, ID or contact details. */
export function toUpdateRequest(fields: PatientUpdateFields): UpdatePatientRequest {
  return { gender: fields.gender as Gender }
}

/** Feedback when an update failed; never includes patient data. */
export function patientUpdateErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Unable to reach MediCore. Check your connection and try again.'
  if (error.status === 409 && error.code === 'VERSION_CONFLICT') {
    return 'This patient was changed by someone else while you were editing. Cancel and open the profile again to see the latest information.'
  }
  switch (error.status) {
    case 400:
      return 'The patient information was rejected. Check the fields and try again.'
    case 401:
      return 'Your session has ended. Sign in again to update patient information.'
    case 403:
      return 'Only doctor and staff accounts can update patient information.'
    case 404:
      return 'This patient could not be found.'
    case 428:
      return 'The patient information could not be saved. Open the profile again and retry.'
    case 422:
      return 'The patient records service rejected the change. Check the fields and try again.'
    case 502:
    case 503:
      return 'The patient records service is temporarily unavailable, so the changes may not have been saved. Open the profile again to check before retrying.'
    default:
      return 'The patient information could not be saved. Please try again later.'
  }
}
