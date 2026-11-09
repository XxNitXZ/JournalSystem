import { ApiError } from '../../services/apiClient.ts'

/** User feedback for a failed patient registration; never includes the entered data. */
export function patientErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return 'Unable to reach MediCore. Check your connection and try again.'
  }
  if (error.status === 500 && error.code === 'REGISTRY_FAILED') {
    // Registering again would only report a duplicate: the patient exists but is not yet visible in MediCore.
    return 'The patient was saved but could not be added to MediCore. Do not register the patient again; contact support.'
  }
  switch (error.status) {
    case 400:
      return 'The patient details were rejected. Check the fields and try again.'
    case 401:
      return 'Your session has ended. Sign in again to register patients.'
    case 403:
      return 'Only doctor and staff accounts can register patients.'
    case 409:
      return 'A patient with this personnummer is already registered.'
    case 422:
      return 'The patient record could not be saved. Check the details and try again.'
    case 502:
    case 503:
      // The registration may have been completed; repeating it is safe because a duplicate is reported.
      return 'The patient records service did not confirm the registration. Try again later; if the patient was already registered, you will be told so.'
    default:
      return 'Patient registration failed. Please try again later.'
  }
}
