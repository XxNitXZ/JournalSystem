import type { HealthResponse } from '../types/health.ts'
import { apiGet, type ApiRequestOptions } from './apiClient.ts'

/** GET /api/health - checks that MediCore is reachable. */
export function getHealth(options?: ApiRequestOptions): Promise<HealthResponse> {
  return apiGet<HealthResponse>('/api/health', options)
}
