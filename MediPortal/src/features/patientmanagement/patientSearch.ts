import { ApiError } from '../../services/apiClient.ts'

/** User feedback for a failed search; never repeats the search text. */
export function patientSearchErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Unable to reach MediCore. Check your connection and try again.'
  }
  if (error.status === 422 && error.code === 'TOO_MANY_RESULTS') {
    return 'Too many patients match. Please refine your search.'
  }
  switch (error.status) {
    case 400:
      return 'Enter at least two letters of a name, or a complete personnummer.'
    case 401:
      return 'Your session has ended. Sign in again to search for patients.'
    case 403:
      return 'Only doctor and staff accounts can search for patients.'
    case 502:
    case 503:
      return 'The patient records service is temporarily unavailable. Please try again later.'
    default:
      return 'The search could not be completed. Please try again later.'
  }
}
