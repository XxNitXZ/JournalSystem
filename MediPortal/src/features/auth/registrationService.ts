import { apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type { RegistrationRequest, RegistrationResponse } from './types.ts'

export function registerUser(request: RegistrationRequest, options?: ApiRequestOptions): Promise<RegistrationResponse> {
  return apiPost<RegistrationResponse>('/api/auth/register', request, options)
}
