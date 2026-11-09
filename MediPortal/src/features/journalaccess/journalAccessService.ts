import { apiGet, apiPost, type ApiRequestOptions } from '../../services/apiClient.ts'
import type {
  JournalAccessRequestDetail,
  JournalAccessRequestSummary,
  PatientJournalStatus,
} from './types.ts'

const BASE = '/api/journal-access-requests'

/** The signed-in PATIENT's journal access status (PATIENT only). */
export async function getMyJournalAccessStatus(options?: ApiRequestOptions): Promise<PatientJournalStatus> {
  const { status } = await apiGet<{ status: PatientJournalStatus }>('/api/journal-access/me', options)
  return status
}

/**
 * The signed-in PATIENT asks for access to their medical record (PATIENT only). Sends no data: MediCore uses the
 * session's account and finds the record itself. Repeated calls return the existing request.
 */
export async function requestJournalAccess(options?: ApiRequestOptions): Promise<PatientJournalStatus> {
  const { status } = await apiPost<{ status: PatientJournalStatus }>('/api/journal-access/me/request', {}, options)
  return status
}

/** All journal access requests, pending first (DOCTOR only). */
export function listJournalAccessRequests(options?: ApiRequestOptions): Promise<JournalAccessRequestSummary[]> {
  return apiGet<JournalAccessRequestSummary[]>(BASE, options)
}

/** Number of requests awaiting review (DOCTOR only). */
export async function getPendingJournalAccessCount(options?: ApiRequestOptions): Promise<number> {
  const { pending } = await apiGet<{ pending: number }>(`${BASE}/pending/count`, options)
  return pending
}

export function getJournalAccessRequest(id: number, options?: ApiRequestOptions): Promise<JournalAccessRequestDetail> {
  return apiGet<JournalAccessRequestDetail>(`${BASE}/${id}`, options)
}

export type JournalAccessDecision = 'approve' | 'reject' | 'reopen'

/** Approves, rejects or reconsiders a request. Never retried automatically. */
export function decideJournalAccessRequest(id: number, decision: JournalAccessDecision,
  options?: ApiRequestOptions): Promise<JournalAccessRequestSummary> {
  return apiPost<JournalAccessRequestSummary>(`${BASE}/${id}/${decision}`, {}, options)
}

/** Fired after a decision so that pending counts elsewhere on the page refresh. */
export const JOURNAL_ACCESS_CHANGED = 'medicore:journal-access-changed'

export function announceJournalAccessChange(): void {
  if (typeof window !== 'undefined') window.dispatchEvent(new Event(JOURNAL_ACCESS_CHANGED))
}
