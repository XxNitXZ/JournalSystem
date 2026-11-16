import { ApiError } from '../../services/apiClient.ts'
import {
  newVisitStatuses,
  visitTypes,
  type EncounterSummary,
  type OrganizationOption,
  type NewEncounterRequest,
  type NewVisitStatus,
  type VisitType,
} from './types.ts'

/** The Create Encounter form as entered. visitTime is the datetime-local value (YYYY-MM-DDTHH:MM, local time). */
export interface EncounterFields {
  organizationId?: string
  visitTime: string
  practitionerId: string
  type: string
  status: string
}

const pad = (value: number) => String(value).padStart(2, '0')

/** A Date as a datetime-local value (local time, minutes), used to prefill the form with "now". */
export function toLocalInputValue(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

/** The local Date of a datetime-local value, or null if it is not a real date and time. */
export function parseLocalInput(value: string): Date | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value.trim())
  if (!match) return null
  const [year, month, day, hour, minute, second] = match.slice(1).map((part) => Number(part ?? 0))
  const date = new Date(year, month - 1, day, hour, minute, second)
  if (date.getFullYear() !== year || date.getMonth() !== month - 1 || date.getDate() !== day
    || date.getHours() !== hour || date.getMinutes() !== minute) return null
  return date
}

/** ISO 8601 with the browser's offset for that moment, e.g. 2026-11-10T14:30:00+01:00. */
export function toIsoWithOffset(date: Date): string {
  const offset = -date.getTimezoneOffset()
  const sign = offset >= 0 ? '+' : '-'
  const absolute = Math.abs(offset)
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:`
    + `${pad(date.getMinutes())}:${pad(date.getSeconds())}${sign}${pad(Math.floor(absolute / 60))}:${pad(absolute % 60)}`
}

/** Five minutes of clock difference are accepted for a visit that has taken place, as in MediCore. */
const SKEW_MS = 5 * 60 * 1000

/** The request, or the first problem with the entered values. Messages never repeat them. */
export function encounterRequest(fields: EncounterFields, now = new Date()):
  { ok: true; request: NewEncounterRequest } | { ok: false; error: string } {
  const visit = parseLocalInput(fields.visitTime)
  if (!visit || visit.getFullYear() < 1900) return { ok: false, error: 'Enter a valid date and time for the visit.' }
  if (!newVisitStatuses.some((status) => status === fields.status)) return { ok: false, error: 'Select a status.' }
  if (fields.status !== 'PLANNED' && visit.getTime() > now.getTime() + SKEW_MS) {
    return { ok: false, error: 'A finished or ongoing visit cannot be in the future. Choose Planned for a future visit.' }
  }
  if (!fields.practitionerId) return { ok: false, error: 'Select a practitioner.' }
  if (!visitTypes.some((type) => type === fields.type)) return { ok: false, error: 'Select a visit type.' }
  if (fields.organizationId && !/^[A-Za-z0-9.-]{1,64}$/.test(fields.organizationId)) {
    return { ok: false, error: 'Select a valid healthcare provider or leave it unselected.' }
  }
  return {
    ok: true,
    request: {
      ...(fields.organizationId ? { organizationId: fields.organizationId } : {}),
      startTime: toIsoWithOffset(visit),
      practitionerId: fields.practitionerId,
      type: fields.type as VisitType,
      status: fields.status as NewVisitStatus,
    },
  }
}

export type SubmitEncounterResult = { ok: true; created: EncounterSummary } | { ok: false; error: string }

/**
 * Validates and creates a visit. After a successful create, onCreated is called (the Encounters section then closes
 * the form and loads the list again); invalid input never reaches MediCore.
 */
export async function submitEncounter(fields: EncounterFields, deps: {
  create: (request: NewEncounterRequest) => Promise<EncounterSummary>
  onCreated: (created: EncounterSummary) => void
}, now = new Date()): Promise<SubmitEncounterResult> {
  const checked = encounterRequest(fields, now)
  if ('error' in checked) return { ok: false, error: checked.error }
  try {
    const created = await deps.create(checked.request)
    deps.onCreated(created)
    return { ok: true, created }
  } catch (failure: unknown) {
    return { ok: false, error: encounterErrorMessage(failure, 'create') }
  }
}

/** A stored start as local date and time ("2026-11-10 14:30"); a date-only value as is; "Date not recorded". */
export function formatVisitDate(start: string | null): string {
  if (!start) return 'Date not recorded'
  if (!start.includes('T')) return start
  const date = new Date(start)
  if (Number.isNaN(date.getTime())) return start
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

const statusLabels: Record<string, string> = {
  planned: 'Planned',
  arrived: 'Arrived',
  triaged: 'Triaged',
  'in-progress': 'In progress',
  onleave: 'On leave',
  finished: 'Finished',
  cancelled: 'Cancelled',
  'entered-in-error': 'Entered in error',
  unknown: 'Unknown',
}

export function formatVisitStatus(status: string): string {
  return statusLabels[status] ?? status
}

/** "Mottagning 3, Vårdcentral" or null. */
export function formatPlace(organization: string | null, location: string | null): string | null {
  const parts = [location, organization].filter((part): part is string => Boolean(part && part.trim()))
  return parts.length ? parts.join(', ') : null
}

const codeMessages: Record<string, string> = {
  START_TIME_INVALID: 'Enter a valid date and time for the visit.',
  START_TIME_IN_FUTURE: 'A finished or ongoing visit cannot be in the future. Choose Planned for a future visit.',
  TYPE_INVALID: 'Select a visit type.',
  STATUS_INVALID: 'Select a status.',
  PRACTITIONER_REQUIRED: 'Select a practitioner.',
  PRACTITIONER_NOT_AVAILABLE: 'The selected practitioner cannot be used. Choose another practitioner.',
  PRACTITIONER_NOT_FOUND: 'The selected practitioner no longer exists. Reload the page and choose again.',
  ORGANIZATION_NOT_FOUND: 'The selected organization no longer exists.',
  LOCATION_NOT_FOUND: 'The selected location no longer exists.',
  RECORD_NOT_LINKED: 'You do not have access to a medical record yet.',
}

/** Feedback for a failed list or create; never repeats entered values or server details. */
export function encounterErrorMessage(error: unknown, action: 'load' | 'create'): string {
  if (!(error instanceof ApiError)) return 'Unable to reach MediCore. Check your connection and try again.'
  if (error.code && codeMessages[error.code]) return codeMessages[error.code]
  switch (error.status) {
    case 400:
      return 'The visit details were rejected. Check the fields and try again.'
    case 401:
      return 'Your session has ended. Sign in again to continue.'
    case 403:
      return action === 'create' ? 'Only doctor and staff accounts can create encounters.'
        : 'You do not have access to these encounters.'
    case 404:
      return 'This patient could not be found.'
    case 502:
    case 503:
      return action === 'create'
        ? 'The patient records service is temporarily unavailable, so the visit may not have been saved. Reload the list before trying again.'
        : 'The patient records service is temporarily unavailable. Please try again later.'
    default:
      return action === 'create' ? 'The encounter could not be created. Please try again later.'
        : 'The encounters could not be loaded. Please try again later.'
  }
}

/** Keep distinct IDs and the selected option even when a new search does not match it. */
export function filterOrganizations(organizations: OrganizationOption[], query: string, selectedId = ''): OrganizationOption[] {
  const search = query.trim().toLocaleLowerCase()
  return organizations.filter((item) => item.id === selectedId || item.name.toLocaleLowerCase().includes(search))
}

export function organizationLabel(item: OrganizationOption, organizations: OrganizationOption[]): string {
  return organizations.some((other) => other.id !== item.id && other.name.toLocaleLowerCase() === item.name.toLocaleLowerCase())
    ? `${item.name} (${item.id})` : item.name
}
