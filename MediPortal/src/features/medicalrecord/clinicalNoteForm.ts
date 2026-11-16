import { ApiError } from '../../services/apiClient.ts'
import { formatVisitDate } from './encounterForm.ts'
import type { ClinicalNote, EncounterSummary, NewClinicalNoteRequest, NoteEncounter } from './types.ts'

/** Maximum note length in characters (Unicode code points), as in MediCore. */
export const MAX_NOTE_LENGTH = 10_000

/** The text as MediCore stores it: line endings as \n, surrounding whitespace removed, inner line breaks kept. */
export function normalizeNoteContent(content: string): string {
  return content.replace(/\r\n?/g, '\n').trim()
}

/** Length in characters (code points, so an emoji counts once), as MediCore counts it. */
export function noteLength(content: string): number {
  return [...normalizeNoteContent(content)].length
}

/** The Create Clinical Note form as entered. */
export interface NoteFields {
  encounterId: string
  content: string
}

// Control characters other than tab and line breaks (C0, DEL, C1), as rejected by MediCore.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]/

/** The request, or the first problem with the entered values. Messages never repeat them. */
export function clinicalNoteRequest(fields: NoteFields):
  { ok: true; request: NewClinicalNoteRequest } | { ok: false; error: string } {
  if (!fields.encounterId) return { ok: false, error: 'Select the encounter this note belongs to.' }
  const content = normalizeNoteContent(fields.content)
  if (!content) return { ok: false, error: 'Enter the note text.' }
  if ([...content].length > MAX_NOTE_LENGTH) {
    return { ok: false, error: `The note can be at most ${MAX_NOTE_LENGTH.toLocaleString('en-US')} characters.` }
  }
  if (CONTROL.test(content)) return { ok: false, error: 'The note contains characters that cannot be saved.' }
  return { ok: true, request: { encounterId: fields.encounterId, content } }
}

/**
 * Runs one task at a time: a call while a task is still running is ignored (null) instead of sending a second
 * request, so a double click never saves a note twice.
 */
export function createSubmitGuard() {
  let running = false
  return async <T>(task: () => Promise<T>): Promise<T | null> => {
    if (running) return null
    running = true
    try {
      return await task()
    } finally {
      running = false
    }
  }
}

export type SubmitNoteResult = { ok: true; created: ClinicalNote } | { ok: false; error: string }

/**
 * Validates and saves a note. After a successful save, onCreated is called (the Clinical Notes section then closes
 * the form and loads the notes again); invalid input never reaches MediCore.
 */
export async function submitClinicalNote(fields: NoteFields, deps: {
  create: (request: NewClinicalNoteRequest) => Promise<ClinicalNote>
  onCreated: (created: ClinicalNote) => void
}): Promise<SubmitNoteResult> {
  const checked = clinicalNoteRequest(fields)
  if ('error' in checked) return { ok: false, error: checked.error }
  try {
    const created = await deps.create(checked.request)
    deps.onCreated(created)
    return { ok: true, created }
  } catch (failure: unknown) {
    return { ok: false, error: clinicalNoteErrorMessage(failure, 'create') }
  }
}

/** "2026-11-10 14:30 · Consultation", for the encounter selector and a note's visit. */
export function formatEncounterLabel(encounter: Pick<EncounterSummary, 'start' | 'type'> | NoteEncounter): string {
  return `${formatVisitDate(encounter.start)} · ${encounter.type ?? 'Visit type not recorded'}`
}

/** The note's creation time in local time, or "Date not recorded". */
export function formatNoteDate(createdAt: string | null): string {
  return formatVisitDate(createdAt)
}

const statusLabels: Record<string, string> = {
  superseded: 'Superseded',
  'entered-in-error': 'Entered in error',
}

/** A label for a status other than "current" (shown as a badge), otherwise null. */
export function noteStatusLabel(status: string | null): string | null {
  return status && status !== 'current' ? statusLabels[status] ?? status : null
}

const maxLengthMessage = `The note can be at most ${MAX_NOTE_LENGTH.toLocaleString('en-US')} characters.`

const codeMessages: Record<string, string> = {
  ENCOUNTER_REQUIRED: 'Select the encounter this note belongs to.',
  ENCOUNTER_INVALID: 'Select the encounter this note belongs to.',
  ENCOUNTER_NOT_FOUND: 'The selected encounter no longer exists. Reload the page and choose again.',
  ENCOUNTER_NOT_FOR_PATIENT: 'The selected encounter does not belong to this patient. Reload the page and choose again.',
  CONTENT_REQUIRED: 'Enter the note text.',
  CONTENT_TOO_LONG: maxLengthMessage,
  CONTENT_INVALID: 'The note contains characters that cannot be saved.',
  PRACTITIONER_NOT_LINKED:
    'Your account is not linked to a practitioner, so you cannot sign clinical notes. Contact an administrator.',
  PRACTITIONER_NOT_FOUND: 'The practitioner linked to your account no longer exists. Contact an administrator.',
  RECORD_NOT_LINKED: 'You do not have access to a medical record yet.',
  INVALID_REQUEST: 'The note was rejected. Check the fields and try again.',
}

/** Feedback for a failed list or create; never repeats the entered text or server details. */
export function clinicalNoteErrorMessage(error: unknown, action: 'load' | 'create'): string {
  if (!(error instanceof ApiError)) {
    return action === 'create'
      ? 'Unable to reach MediCore, so the note may not have been saved. Refresh the notes before trying again.'
      : 'Unable to reach MediCore. Check your connection and try again.'
  }
  if (error.code && codeMessages[error.code]) return codeMessages[error.code]
  switch (error.status) {
    case 400:
      return 'The note was rejected. Check the fields and try again.'
    case 401:
      return 'Your session has ended. Sign in again to continue.'
    case 403:
      return action === 'create' ? 'Only doctor and staff accounts can write clinical notes.'
        : 'You do not have access to these clinical notes.'
    case 404:
      return 'This patient could not be found.'
    case 502:
    case 503:
    case 504:
      return action === 'create'
        ? 'The patient records service did not confirm the note, so it may or may not have been saved. Refresh the notes before trying again.'
        : 'The patient records service is temporarily unavailable. Please try again later.'
    default:
      return action === 'create' ? 'The note could not be saved. Please try again later.'
        : 'The clinical notes could not be loaded. Please try again later.'
  }
}
