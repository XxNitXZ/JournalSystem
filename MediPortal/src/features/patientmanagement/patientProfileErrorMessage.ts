import { ApiError } from '../../services/apiClient.ts'

/** User feedback for a patient profile that could not be loaded; never includes patient data. */
export function patientProfileErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Unable to reach MediCore. Check your connection and try again.'
  }
  switch (error.status) {
    case 400:
      return 'The patient ID is not valid.'
    case 401:
      return 'Your session has ended. Sign in again to view patient profiles.'
    case 403:
      return 'Only doctor and staff accounts can view patient profiles.'
    case 404:
      return 'No patient with this ID was found.'
    case 502:
    case 503:
      return 'The patient records service is temporarily unavailable. Please try again later.'
    default:
      return 'The patient profile could not be loaded. Please try again later.'
  }
}
