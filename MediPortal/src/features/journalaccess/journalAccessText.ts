import { ApiError } from '../../services/apiClient.ts'
import type { JournalAccessDecision } from './journalAccessService.ts'
import type { JournalAccessRequestStatus, PatientJournalStatus } from './types.ts'

/** The exact dashboard texts for each PATIENT journal access status. */
export const patientStatusText: Record<PatientJournalStatus, string> = {
  NONE: 'You have not requested access to your medical record.',
  AWAITING_RECORD: 'Your request has been received. We are waiting for your medical record to be registered.',
  PENDING: 'Your journal access request is awaiting doctor approval.',
  APPROVED: 'You have access to your medical record.',
  REJECTED: 'Your journal access request was not approved.',
}

/** What a rejected patient can do; a rejected request is never resubmitted automatically. */
export const rejectedExplanation =
  'If you believe this is a mistake, contact your care provider. A doctor can reconsider your request.'

export const statusTones: Record<JournalAccessRequestStatus, 'neutral' | 'warning' | 'success' | 'danger'> = {
  AWAITING_RECORD: 'neutral',
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
}

/** A request ID from the URL: a positive whole number, otherwise null. */
export function parseRequestId(value: string | undefined): number | null {
  if (!value || !/^[1-9][0-9]{0,15}$/.test(value)) return null
  const id = Number(value)
  return Number.isSafeInteger(id) ? id : null
}

export const requestStatusLabels: Record<JournalAccessRequestStatus, string> = {
  AWAITING_RECORD: 'Awaiting record',
  PENDING: 'Pending',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
}

/** "First Last", or "Name not on file" for accounts created before names were stored. */
export function personName(givenName: string | null | undefined, familyName: string | null | undefined): string {
  const name = [givenName, familyName].map((part) => part?.trim()).filter(Boolean).join(' ')
  return name || 'Name not on file'
}

/** A 12-digit personnummer as YYYYMMDD-NNNN; "Not on file" if missing. */
export function formatPersonnummer(value: string | null | undefined): string {
  if (!value) return 'Not on file'
  return /^\d{12}$/.test(value) ? `${value.slice(0, 8)}-${value.slice(8)}` : value
}

/** An ISO instant as a local date and time (YYYY-MM-DD HH:MM). */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export const decisionSuccess: Record<JournalAccessDecision, string> = {
  approve: 'The request has been approved. The patient now has access to their medical record.',
  reject: 'The request has been rejected. The patient has not been given access.',
  reopen: 'The request has been reopened and is awaiting review again.',
}

const conflictMessages: Record<string, string> = {
  REQUEST_NOT_PENDING: 'This request has already been decided, possibly by another doctor. Reload to see its current status.',
  REQUEST_NOT_REJECTED: 'Only rejected requests can be reconsidered.',
  AWAITING_RECORD: 'No patient record has been registered for this personnummer yet. The request cannot be decided.',
  ALREADY_PENDING: 'A request for this patient is already awaiting review.',
  ACCOUNT_NOT_ELIGIBLE: 'This account cannot be given journal access.',
  LINK_CONFLICT: 'The account or the medical record is already linked to someone else. The request cannot be approved.',
  FHIR_PATIENT_MISSING: 'The medical record no longer exists. The request cannot be approved.',
  PERSONNUMMER_MISMATCH: 'The personnummer of the account and the medical record do not match. The request cannot be approved.',
}

/** Feedback for a failed list, detail or decision; never includes personal data. */
export function journalAccessErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Unable to reach MediCore. Check your connection and try again.'
  switch (error.status) {
    case 400:
      return 'This journal access request was not found.'
    case 401:
      return 'Your session has ended. Sign in again to continue.'
    case 403:
      return 'Only doctor accounts can review journal access requests.'
    case 404:
      return 'This journal access request was not found.'
    case 409:
      return (error.code && conflictMessages[error.code]) || 'The request could not be updated. Reload and try again.'
    case 502:
    case 503:
      return 'The patient records service is temporarily unavailable. Please try again later.'
    default:
      return 'The request could not be completed. Please try again later.'
  }
}

const requestConflictMessages: Record<string, string> = {
  REQUEST_REJECTED: 'Your previous request was not approved, so a new request cannot be sent. Please contact your care provider.',
  PERSONNUMMER_MISSING: 'Your account has no personnummer on file, so access cannot be requested. Please contact your care provider.',
  LINK_CONFLICT: 'Your request could not be sent. Please contact your care provider.',
  ACCOUNT_NOT_ELIGIBLE: 'This account cannot request access to a medical record.',
}

/** Feedback when the PATIENT's request for access could not be sent; never includes personal data. */
export function requestAccessErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Unable to reach MediCore. Check your connection and try again.'
  if (error.status === 409) {
    return (error.code && requestConflictMessages[error.code]) || 'Your request could not be sent. Please try again later.'
  }
  if (error.status === 401) return 'Your session has ended. Sign in again to request access.'
  if (error.status === 403) return 'Only patient accounts can request access to a medical record.'
  return 'Your request could not be sent. Please try again later.'
}

/** Feedback when the PATIENT's own record cannot be shown. */
export function ownRecordErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return 'Unable to reach MediCore. Check your connection and try again.'
  if (error.status === 404 && error.code === 'RECORD_NOT_LINKED') {
    return 'You do not have access to a medical record yet.'
  }
  if (error.status === 404) return 'Your medical record could not be found. Please contact your care provider.'
  if (error.status === 401) return 'Your session has ended. Sign in again to view your medical record.'
  if (error.status === 403) return 'Only patient accounts have a personal medical record.'
  if (error.status === 502 || error.status === 503) {
    return 'The patient records service is temporarily unavailable. Please try again later.'
  }
  return 'Your medical record could not be loaded. Please try again later.'
}
