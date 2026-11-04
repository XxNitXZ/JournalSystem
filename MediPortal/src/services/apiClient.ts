/**
 * Minimal reusable client for the MediCore REST API, built on the native
 * fetch API.
 *
 * - Base URL: VITE_API_BASE_URL (see .env.example), default http://localhost:8080.
 * - Non-2xx responses are rejected with an ApiError.
 * - Network failures (backend down, CORS rejection, abort) are not caught:
 *   the original error from fetch is propagated to the caller.
 */

const DEFAULT_API_BASE_URL = 'http://localhost:8080'

function resolveBaseUrl(value: string | undefined): string {
  const trimmed = value?.trim()
  return (trimmed ? trimmed : DEFAULT_API_BASE_URL).replace(/\/+$/, '')
}

export const API_BASE_URL = resolveBaseUrl(import.meta.env.VITE_API_BASE_URL)

/** Thrown when the backend answers with a non-successful HTTP status. */
export class ApiError extends Error {
  readonly status: number
  readonly url: string

  constructor(status: number, statusText: string, url: string) {
    super(`Request to ${url} failed with HTTP ${status}${statusText ? ` ${statusText}` : ''}`)
    this.name = 'ApiError'
    this.status = status
    this.url = url
  }
}

export interface ApiRequestOptions {
  /** Lets the caller cancel the request, e.g. when a component unmounts. */
  signal?: AbortSignal
}

/**
 * Sends a GET request to `path` (starting with "/", e.g. "/api/health") and
 * returns the parsed JSON body typed as T.
 */
export async function apiGet<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const url = `${API_BASE_URL}${path}`

  const response = await fetch(url, {
    method: 'GET',
    headers: { Accept: 'application/json' },
    signal: options.signal,
  })

  if (!response.ok) {
    throw new ApiError(response.status, response.statusText, url)
  }

  return (await response.json()) as T
}
