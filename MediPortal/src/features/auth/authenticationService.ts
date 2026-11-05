import { ApiError, apiGet, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type { LoginRequest, UserDto } from './types.ts'

export function login(request: LoginRequest, options?: ApiRequestOptions): Promise<UserDto> {
  return apiPost<UserDto>('/api/auth/login', request, options)
}

export async function getCurrentUser(options?: ApiRequestOptions): Promise<UserDto | null> {
  try {
    return await apiGet<UserDto>('/api/auth/me', options)
  } catch (error: unknown) {
    if (error instanceof ApiError && error.status === 401) return null
    throw error
  }
}

export function logout(options?: ApiRequestOptions): Promise<void> {
  return apiPost<void>('/api/auth/logout', undefined, options)
}
