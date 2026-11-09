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
  /** Machine-readable reason from a {"code": "..."} error body, if MediCore sent one. */
  readonly code?: string

  constructor(status: number, statusText: string, url: string, code?: string) {
    super(`Request to ${url} failed with HTTP ${status}${statusText ? ` ${statusText}` : ''}`)
    this.name = 'ApiError'
    this.status = status
    this.url = url
    this.code = code
  }
}

/** An ApiError for a failed response, with the error code from a JSON body when there is a valid one. */
async function apiError(response: Response, url: string): Promise<ApiError> {
  let code: string | undefined
  try {
    const body: unknown = await response.json()
    const value = (body as { code?: unknown } | null)?.code
    if (typeof value === 'string' && /^[A-Z_]{1,64}$/.test(value)) code = value
  } catch {
    // Empty or non-JSON error bodies carry no code.
  }
  return new ApiError(response.status, response.statusText, url, code)
}

export interface ApiRequestOptions {
  /** Lets the caller cancel the request, e.g. when a component unmounts. */
  signal?: AbortSignal
  /** Extra request headers for POST and PATCH, e.g. If-Match; they never replace the CSRF or content headers. */
  headers?: Record<string, string>
}


export async function apiGet<T>(path: string, options: ApiRequestOptions = {}): Promise<T> {
  const url = `${API_BASE_URL}${path}`

  const response = await fetch(url, {
    method: 'GET',
    credentials: 'include',
    headers: { Accept: 'application/json' },
    signal: options.signal,
  })

  if (!response.ok) {
    throw await apiError(response, url)
  }

  return (await response.json()) as T
}

/** Sends JSON and preserves the same HTTP and network errors as apiGet. */
export function apiPost<T>(path: string, body: unknown, options: ApiRequestOptions = {}): Promise<T> {
  return sendJson<T>('POST', path, body, options)
}

/** Partially updates a resource with JSON; same CSRF handling and errors as apiPost. */
export function apiPatch<T>(path: string, body: unknown, options: ApiRequestOptions = {}): Promise<T> {
  return sendJson<T>('PATCH', path, body, options)
}

async function sendJson<T>(method: 'POST' | 'PATCH', path: string, body: unknown, options: ApiRequestOptions): Promise<T> {
  const csrf = await apiGet<{ token: string; headerName: string }>('/api/auth/csrf', options)
  const url = `${API_BASE_URL}${path}`
  const response = await fetch(url, {
    method,
    credentials: 'include',
    headers: { ...options.headers, Accept: 'application/json', 'Content-Type': 'application/json',
      [csrf.headerName]: csrf.token },
    body: JSON.stringify(body),
    signal: options.signal,
  })
  if (!response.ok) {
    throw await apiError(response, url)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}
