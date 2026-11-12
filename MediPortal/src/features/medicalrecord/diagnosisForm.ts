import { ApiError } from '../../services/apiClient.ts'
import {
  clinicalStatuses,
  diagnosisCodeSystems,
  newVerificationStatuses,
  type ClinicalStatus,
  type Diagnosis,
  type DiagnosisCode,
  type DiagnosisCodeSystem,
  type NewDiagnosisRequest,
  type NewVerificationStatus,
} from './types.ts'

/** Maximum diagnosis text length in characters, as in MediCore. */
export const MAX_DIAGNOSIS_LENGTH = 250

/** The Add Diagnosis form as entered. codeSystem is "" when no code is given. */
export interface DiagnosisFields {
  diagnosis: string
  code: string
  codeSystem: string
  clinicalStatus: string
  verificationStatus: string
  onsetDate: string
  encounterId: string
}

export type DiagnosisField = keyof DiagnosisFields

/** A new form: no code, active and confirmed, no onset and no encounter. */
export const emptyDiagnosisFields: DiagnosisFields = {
  diagnosis: '', code: '', codeSystem: '', clinicalStatus: 'ACTIVE', verificationStatus: 'CONFIRMED', onsetDate: '',
  encounterId: '',
}

const pad = (value: number) => String(value).padStart(2, '0')

/** Today's local date as YYYY-MM-DD (the latest allowed onset). */
export function localToday(now = new Date()): string {
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}

const D = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 2, 3, 4, 0, 6, 7, 8, 9, 5], [2, 3, 4, 0, 1, 7, 8, 9, 5, 6],
  [3, 4, 0, 1, 2, 8, 9, 5, 6, 7], [4, 0, 1, 2, 3, 9, 5, 6, 7, 8], [5, 9, 8, 7, 6, 0, 4, 3, 2, 1],
  [6, 5, 9, 8, 7, 1, 0, 4, 3, 2], [7, 6, 5, 9, 8, 2, 1, 0, 4, 3], [8, 7, 6, 5, 9, 3, 2, 1, 0, 4],
  [9, 8, 7, 6, 5, 4, 3, 2, 1, 0]]
const P = [[0, 1, 2, 3, 4, 5, 6, 7, 8, 9], [1, 5, 7, 6, 2, 8, 3, 0, 9, 4], [5, 8, 0, 3, 7, 9, 6, 1, 4, 2],
  [8, 9, 1, 6, 0, 4, 3, 5, 2, 7], [9, 4, 5, 3, 1, 2, 6, 8, 7, 0], [4, 2, 8, 6, 5, 7, 3, 9, 0, 1],
  [2, 7, 9, 3, 8, 0, 6, 4, 1, 5], [7, 0, 4, 6, 9, 1, 3, 2, 5, 8]]

/**
 * The code in its stored form if it is structurally valid in the system, otherwise null: a SNOMED CT concept ID
 * (6–18 digits, concept partition, Verhoeff check digit) or an ICD-10 code (e.g. J45, E11.9; upper case). Whether the
 * code exists is not checked; MediCore checks the same structure.
 */
export function normalizeDiagnosisCode(system: DiagnosisCodeSystem, code: string): string | null {
  const value = code.trim()
  if (system === 'ICD_10') {
    const upper = value.toUpperCase()
    return /^[A-Z][0-9]{2}(\.[0-9A-Z]{1,4})?$/.test(upper) ? upper : null
  }
  if (!/^[1-9][0-9]{5,17}$/.test(value)) return null
  const partition = value.slice(-3, -1)
  if (partition !== '00' && partition !== '10') return null
  let check = 0
  for (let i = 0; i < value.length; i += 1) check = D[check][P[i % 8][Number(value[value.length - 1 - i])]]
  return check === 0 ? value : null
}

// Control characters (C0, DEL, C1), including line breaks: a diagnosis is a single line.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001F\u007F-\u009F]/

export type DiagnosisCheck = { ok: true; request: NewDiagnosisRequest } | { ok: false; field: DiagnosisField; error: string }

/** The request, or the first problem with the entered values and its field. Messages never repeat the values. */
export function diagnosisRequest(fields: DiagnosisFields, now = new Date()): DiagnosisCheck {
  const diagnosis = fields.diagnosis.trim()
  if (!diagnosis) return { ok: false, field: 'diagnosis', error: 'Enter the diagnosis.' }
  if ([...diagnosis].length > MAX_DIAGNOSIS_LENGTH) {
    return { ok: false, field: 'diagnosis', error: `The diagnosis can be at most ${MAX_DIAGNOSIS_LENGTH} characters.` }
  }
  if (CONTROL.test(diagnosis)) return { ok: false, field: 'diagnosis', error: 'The diagnosis must be a single line of text.' }
  const request: NewDiagnosisRequest = { diagnosis, clinicalStatus: 'ACTIVE', verificationStatus: 'CONFIRMED' }
  const code = fields.code.trim()
  if (code || fields.codeSystem) {
    if (!diagnosisCodeSystems.some((system) => system === fields.codeSystem)) {
      return { ok: false, field: 'codeSystem', error: 'Select the code system of the diagnosis code.' }
    }
    if (!code) return { ok: false, field: 'code', error: 'Enter the diagnosis code, or choose "No code".' }
    const system = fields.codeSystem as DiagnosisCodeSystem
    const normalized = normalizeDiagnosisCode(system, code)
    if (!normalized) {
      return { ok: false, field: 'code', error: system === 'SNOMED_CT'
        ? 'This is not a valid SNOMED CT concept ID. Check the code or leave it empty.'
        : 'This is not a valid ICD-10 code (for example J45 or E11.9). Check the code or leave it empty.' }
    }
    request.code = normalized
    request.codeSystem = system
  }
  if (!clinicalStatuses.some((status) => status === fields.clinicalStatus)) {
    return { ok: false, field: 'clinicalStatus', error: 'Select a clinical status.' }
  }
  request.clinicalStatus = fields.clinicalStatus as ClinicalStatus
  if (!newVerificationStatuses.some((status) => status === fields.verificationStatus)) {
    return { ok: false, field: 'verificationStatus', error: 'Select a verification status.' }
  }
  request.verificationStatus = fields.verificationStatus as NewVerificationStatus
  if (fields.onsetDate) {
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(fields.onsetDate)
    const date = match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : null
    if (!match || !date || date.getMonth() !== Number(match[2]) - 1 || date.getDate() !== Number(match[3])
      || Number(match[1]) < 1900) {
      return { ok: false, field: 'onsetDate', error: 'Enter a valid onset date.' }
    }
    if (fields.onsetDate > localToday(now)) return { ok: false, field: 'onsetDate', error: 'The onset date cannot be in the future.' }
    request.onsetDate = fields.onsetDate
  }
  if (fields.encounterId) request.encounterId = fields.encounterId
  return { ok: true, request }
}

export type SubmitDiagnosisResult = { ok: true; created: Diagnosis } | { ok: false; error: string; field?: DiagnosisField }

/**
 * Validates and records a diagnosis. After a successful save, onCreated is called (the Diagnoses section then closes
 * the form and loads the list again); invalid input never reaches MediCore.
 */
export async function submitDiagnosis(fields: DiagnosisFields, deps: {
  create: (request: NewDiagnosisRequest) => Promise<Diagnosis>
  onCreated: (created: Diagnosis) => void
}, now = new Date()): Promise<SubmitDiagnosisResult> {
  const checked = diagnosisRequest(fields, now)
  if ('error' in checked) return { ok: false, error: checked.error, field: checked.field }
  try {
    const created = await deps.create(checked.request)
    deps.onCreated(created)
    return { ok: true, created }
  } catch (failure: unknown) {
    return { ok: false, error: diagnosisErrorMessage(failure, 'create'), field: fieldOf(failure) }
  }
}

const clinicalLabels: Record<string, string> = {
  active: 'Active', recurrence: 'Recurrence', relapse: 'Relapse', inactive: 'Inactive', remission: 'Remission',
  resolved: 'Resolved',
}
const verificationLabels: Record<string, string> = {
  confirmed: 'Confirmed', provisional: 'Provisional', differential: 'Differential', unconfirmed: 'Unconfirmed',
  refuted: 'Refuted', 'entered-in-error': 'Entered in error',
}
const categoryLabels: Record<string, string> = {
  'encounter-diagnosis': 'Encounter diagnosis', 'problem-list-item': 'Problem list',
}

export const clinicalStatusLabels: Record<ClinicalStatus, string> = {
  ACTIVE: 'Active', RECURRENCE: 'Recurrence', RELAPSE: 'Relapse', INACTIVE: 'Inactive', REMISSION: 'Remission',
  RESOLVED: 'Resolved',
}
export const verificationStatusLabels: Record<NewVerificationStatus, string> = {
  CONFIRMED: 'Confirmed', PROVISIONAL: 'Provisional', DIFFERENTIAL: 'Differential', UNCONFIRMED: 'Unconfirmed',
}

/** A readable label for a FHIR status or category code; an unknown value is shown as stored; null stays null. */
export function formatClinicalStatus(status: string | null): string | null {
  return status ? clinicalLabels[status] ?? status : null
}

export function formatVerificationStatus(status: string | null): string | null {
  return status ? verificationLabels[status] ?? status : null
}

export function formatCategory(category: string | null): string | null {
  return category ? categoryLabels[category] ?? category : null
}

/** "SNOMED CT 44054006", "ICD-10 E11.9", or the code alone for another system. */
export function formatDiagnosisCode(code: DiagnosisCode | null): string | null {
  if (!code) return null
  return code.systemName ? `${code.systemName} ${code.code}` : code.code
}

/** The name to show: the diagnosis text, otherwise the code, otherwise "Diagnosis not recorded". */
export function diagnosisTitle(diagnosis: Diagnosis): string {
  return diagnosis.diagnosis ?? formatDiagnosisCode(diagnosis.code) ?? 'Diagnosis not recorded'
}


const fieldCodes: Record<string, DiagnosisField> = {
  DIAGNOSIS_REQUIRED: 'diagnosis', DIAGNOSIS_TOO_LONG: 'diagnosis', DIAGNOSIS_INVALID: 'diagnosis',
  CODE_SYSTEM_REQUIRED: 'codeSystem', CODE_SYSTEM_INVALID: 'codeSystem', CODE_REQUIRED: 'code', CODE_INVALID: 'code',
  CLINICAL_STATUS_INVALID: 'clinicalStatus', VERIFICATION_STATUS_INVALID: 'verificationStatus',
  ONSET_INVALID: 'onsetDate', ONSET_IN_FUTURE: 'onsetDate', ENCOUNTER_INVALID: 'encounterId',
  ENCOUNTER_NOT_FOUND: 'encounterId', ENCOUNTER_NOT_FOR_PATIENT: 'encounterId',
}

function fieldOf(error: unknown): DiagnosisField | undefined {
  return error instanceof ApiError && error.code ? fieldCodes[error.code] : undefined
}

const codeMessages: Record<string, string> = {
  DIAGNOSIS_REQUIRED: 'Enter the diagnosis.',
  DIAGNOSIS_TOO_LONG: `The diagnosis can be at most ${MAX_DIAGNOSIS_LENGTH} characters.`,
  DIAGNOSIS_INVALID: 'The diagnosis must be a single line of text.',
  CODE_SYSTEM_REQUIRED: 'Select the code system of the diagnosis code.',
  CODE_SYSTEM_INVALID: 'Select the code system of the diagnosis code.',
  CODE_REQUIRED: 'Enter the diagnosis code, or choose "No code".',
  CODE_INVALID: 'The diagnosis code is not valid in the selected code system.',
  CLINICAL_STATUS_INVALID: 'Select a clinical status.',
  VERIFICATION_STATUS_INVALID: 'Select a verification status.',
  ONSET_INVALID: 'Enter a valid onset date.',
  ONSET_IN_FUTURE: 'The onset date cannot be in the future.',
  ENCOUNTER_INVALID: 'Select one of this patient\'s encounters, or none.',
  ENCOUNTER_NOT_FOUND: 'The selected encounter no longer exists. Reload the page and choose again.',
  ENCOUNTER_NOT_FOR_PATIENT: 'The selected encounter does not belong to this patient. Reload the page and choose again.',
  PRACTITIONER_NOT_LINKED:
    'Your account is not linked to a practitioner, so you cannot record diagnoses. Contact an administrator.',
  PRACTITIONER_NOT_FOUND: 'The practitioner linked to your account no longer exists. Contact an administrator.',
  RECORD_NOT_LINKED: 'You do not have access to a medical record yet.',
  INVALID_REQUEST: 'The diagnosis was rejected. Check the fields and try again.',
}

/** Feedback for a failed list or create; never repeats entered values or server details. */
export function diagnosisErrorMessage(error: unknown, action: 'load' | 'create'): string {
  if (!(error instanceof ApiError)) {
    return action === 'create'
      ? 'Unable to reach MediCore, so the diagnosis may not have been saved. Refresh the diagnoses before trying again.'
      : 'Unable to reach MediCore. Check your connection and try again.'
  }
  if (error.code && codeMessages[error.code]) return codeMessages[error.code]
  switch (error.status) {
    case 400:
      return 'The diagnosis was rejected. Check the fields and try again.'
    case 401:
      return 'Your session has ended. Sign in again to continue.'
    case 403:
      return action === 'create' ? 'Only doctor and staff accounts can record diagnoses.'
        : 'You do not have access to these diagnoses.'
    case 404:
      return 'This patient could not be found.'
    case 502:
    case 503:
    case 504:
      return action === 'create'
        ? 'The patient records service did not confirm the diagnosis, so it may or may not have been saved. Refresh the diagnoses before trying again.'
        : 'The patient records service is temporarily unavailable. Please try again later.'
    default:
      return action === 'create' ? 'The diagnosis could not be saved. Please try again later.'
        : 'The diagnoses could not be loaded. Please try again later.'
  }
}
