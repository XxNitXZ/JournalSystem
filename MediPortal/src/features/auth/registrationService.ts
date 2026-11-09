import { apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import { getCurrentUser } from './authenticationService.ts'
import type { RegistrationRequest, RegistrationResponse, UserDto } from './types.ts'

export function registerUser(request: RegistrationRequest, options?: ApiRequestOptions): Promise<RegistrationResponse> {
  return apiPost<RegistrationResponse>('/api/auth/register', request, options)
}

export interface RegistrationResult {
  account: RegistrationResponse
  /** The signed-in user if MediCore started a session for the new account (PATIENT only), otherwise null. */
  session: UserDto | null
}

/**
 * Registers an account and then asks MediCore whether it started a session for it. Registration errors are
 * rejected unchanged; a failure to read the session after a successful registration only means "not signed in".
 */
export async function registerAccount(request: RegistrationRequest, options?: ApiRequestOptions): Promise<RegistrationResult> {
  const account = await registerUser(request, options)
  let session: UserDto | null
  try {
    const current = await getCurrentUser(options)
    // Only the new account's own session counts.
    session = current && current.id === account.id ? current : null
  } catch {
    session = null
  }
  return { account, session }
}
