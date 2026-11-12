import { useEffect, useRef, useState, type ReactNode, type SubmitEvent } from 'react'
import { Stethoscope, X } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import { createSubmitGuard, formatEncounterLabel } from './clinicalNoteForm.ts'
import { createDiagnosis } from './diagnosisService.ts'
import {
  MAX_DIAGNOSIS_LENGTH,
  clinicalStatusLabels,
  emptyDiagnosisFields,
  localToday,
  submitDiagnosis,
  verificationStatusLabels,
  type DiagnosisField,
  type DiagnosisFields,
} from './diagnosisForm.ts'
import { encounterErrorMessage } from './encounterForm.ts'
import { listPatientEncounters } from './encounterService.ts'
import {
  clinicalStatuses,
  diagnosisCodeSystemLabels,
  diagnosisCodeSystems,
  newVerificationStatuses,
  type Diagnosis,
  type EncounterSummary,
} from './types.ts'

export type DiagnosisEncounterState = { status: 'loading' } | { status: 'failed'; message: string }
  | { status: 'loaded'; encounters: EncounterSummary[] }

export interface FieldError {
  field: DiagnosisField
  message: string
}

interface CreateDiagnosisFormViewProps {
  encounterState: DiagnosisEncounterState
  fields: DiagnosisFields
  fieldError: FieldError | null
  pending: boolean
  error: string
  /** Latest selectable onset date (today, local). */
  today: string
  onChange: (field: DiagnosisField, value: string) => void
  onSubmit: (event: SubmitEvent<HTMLFormElement>) => void
  onCancel: () => void
}

/** The error of one field, linked to its control by ID. */
function FieldMessage({ field, fieldError }: { field: DiagnosisField; fieldError: FieldError | null }): ReactNode {
  if (fieldError?.field !== field) return null
  return <p id={`diagnosis-${field}-error`} className="field__error" role="alert">{fieldError.message}</p>
}

/**
 * The Add Diagnosis form. The patient is the one whose record is open and the recorder is the signed-in clinician;
 * neither is a field. The code is optional (a diagnosis without one is stored as text only); the visit is optional
 * and chosen by date and type. Exported for rendering tests.
 */
export function CreateDiagnosisFormView({ encounterState, fields, fieldError, pending, error, today, onChange,
  onSubmit, onCancel }: CreateDiagnosisFormViewProps) {
  const encounters = encounterState.status === 'loaded' ? encounterState.encounters : []
  const invalid = (field: DiagnosisField) => (fieldError?.field === field ? true : undefined)
  const describedBy = (field: DiagnosisField, hintId?: string) =>
    [hintId, fieldError?.field === field ? `diagnosis-${field}-error` : undefined].filter(Boolean).join(' ') || undefined
  return (
    <form className="form encounter-form" onSubmit={onSubmit} aria-busy={pending}
      aria-labelledby="create-diagnosis-title" noValidate>
      <h3 id="create-diagnosis-title">Add Diagnosis</h3>
      <fieldset disabled={pending}>
        <legend className="visually-hidden">Diagnosis</legend>
        <FormField htmlFor="diagnosis-text" label="Diagnosis" hintId="diagnosis-text-help"
          hint={`Required, at most ${MAX_DIAGNOSIS_LENGTH} characters.`}>
          <input id="diagnosis-text" name="diagnosis" value={fields.diagnosis} maxLength={MAX_DIAGNOSIS_LENGTH * 2}
            autoComplete="off" required aria-invalid={invalid('diagnosis')}
            aria-describedby={describedBy('diagnosis', 'diagnosis-text-help')}
            onChange={(event) => onChange('diagnosis', event.target.value)} />
          <FieldMessage field="diagnosis" fieldError={fieldError} />
        </FormField>
        <div className="form__row">
          <FormField htmlFor="diagnosis-code-system" label="Code system" hintId="diagnosis-code-system-help"
            hint="Optional. Without a code the diagnosis is saved as text only.">
            <select id="diagnosis-code-system" name="codeSystem" value={fields.codeSystem}
              aria-invalid={invalid('codeSystem')}
              aria-describedby={describedBy('codeSystem', 'diagnosis-code-system-help')}
              onChange={(event) => onChange('codeSystem', event.target.value)}>
              <option value="">No code</option>
              {diagnosisCodeSystems.map((system) => <option key={system} value={system}>{diagnosisCodeSystemLabels[system]}</option>)}
            </select>
            <FieldMessage field="codeSystem" fieldError={fieldError} />
          </FormField>
          <FormField htmlFor="diagnosis-code" label="Diagnosis code" hintId="diagnosis-code-help"
            hint="For example SNOMED CT 44054006 or ICD-10 E11.9.">
            <input id="diagnosis-code" name="code" value={fields.code} maxLength={20} autoComplete="off"
              disabled={!fields.codeSystem} aria-invalid={invalid('code')}
              aria-describedby={describedBy('code', 'diagnosis-code-help')}
              onChange={(event) => onChange('code', event.target.value)} />
            <FieldMessage field="code" fieldError={fieldError} />
          </FormField>
        </div>
        <div className="form__row">
          <FormField htmlFor="diagnosis-clinical-status" label="Clinical status">
            <select id="diagnosis-clinical-status" name="clinicalStatus" value={fields.clinicalStatus}
              aria-invalid={invalid('clinicalStatus')} aria-describedby={describedBy('clinicalStatus')}
              onChange={(event) => onChange('clinicalStatus', event.target.value)}>
              {clinicalStatuses.map((status) => <option key={status} value={status}>{clinicalStatusLabels[status]}</option>)}
            </select>
            <FieldMessage field="clinicalStatus" fieldError={fieldError} />
          </FormField>
          <FormField htmlFor="diagnosis-verification-status" label="Verification status">
            <select id="diagnosis-verification-status" name="verificationStatus" value={fields.verificationStatus}
              aria-invalid={invalid('verificationStatus')} aria-describedby={describedBy('verificationStatus')}
              onChange={(event) => onChange('verificationStatus', event.target.value)}>
              {newVerificationStatuses.map((status) => <option key={status} value={status}>{verificationStatusLabels[status]}</option>)}
            </select>
            <FieldMessage field="verificationStatus" fieldError={fieldError} />
          </FormField>
        </div>
        <div className="form__row">
          <FormField htmlFor="diagnosis-onset" label="Onset date" hintId="diagnosis-onset-help" hint="Optional.">
            <input id="diagnosis-onset" name="onsetDate" type="date" value={fields.onsetDate} max={today} min="1900-01-01"
              aria-invalid={invalid('onsetDate')} aria-describedby={describedBy('onsetDate', 'diagnosis-onset-help')}
              onChange={(event) => onChange('onsetDate', event.target.value)} />
            <FieldMessage field="onsetDate" fieldError={fieldError} />
          </FormField>
          <FormField htmlFor="diagnosis-encounter" label="Encounter" hintId="diagnosis-encounter-help"
            hint="Optional. The visit where the diagnosis was made.">
            <select id="diagnosis-encounter" name="encounterId" value={fields.encounterId}
              disabled={encounterState.status !== 'loaded'} aria-invalid={invalid('encounterId')}
              aria-describedby={describedBy('encounterId', 'diagnosis-encounter-help')}
              onChange={(event) => onChange('encounterId', event.target.value)}>
              <option value="">{encounterState.status === 'loading' ? 'Loading encounters…' : 'No encounter'}</option>
              {encounters.map((encounter) => (
                <option key={encounter.id} value={encounter.id}>{formatEncounterLabel(encounter)}</option>
              ))}
            </select>
            <FieldMessage field="encounterId" fieldError={fieldError} />
          </FormField>
        </div>
        <div className="page-actions">
          <button className="btn btn--primary" type="submit">
            <Stethoscope size={18} aria-hidden="true" />
            {pending ? 'Saving diagnosis…' : 'Save diagnosis'}
          </button>
          <button className="btn btn--secondary" type="button" onClick={onCancel}>
            <X size={18} aria-hidden="true" />Cancel
          </button>
        </div>
      </fieldset>
      {encounterState.status === 'failed' && (
        <Alert tone="info">{encounterState.message} You can still record the diagnosis without an encounter.</Alert>
      )}
      <Alert tone="error" role="alert">{error}</Alert>
    </form>
  )
}

interface CreateDiagnosisFormProps {
  patientId: string
  onCreated: (created: Diagnosis) => void
  onCancel: () => void
  /** Encounters already known (for tests); otherwise they are loaded from MediCore. */
  initialEncounters?: EncounterSummary[]
}

/** Records a diagnosis for the open patient (DOCTOR and STAFF). */
function CreateDiagnosisForm({ patientId, onCreated, onCancel, initialEncounters }: CreateDiagnosisFormProps) {
  const [encounterState, setEncounterState] = useState<DiagnosisEncounterState>(
    initialEncounters ? { status: 'loaded', encounters: initialEncounters } : { status: 'loading' })
  const [fields, setFields] = useState<DiagnosisFields>(emptyDiagnosisFields)
  const [fieldError, setFieldError] = useState<FieldError | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [today] = useState(() => localToday())
  const guard = useRef(createSubmitGuard())

  useEffect(() => {
    if (initialEncounters) return undefined
    const controller = new AbortController()
    listPatientEncounters(patientId, { signal: controller.signal })
      .then((encounters) => {
        if (!controller.signal.aborted) setEncounterState({ status: 'loaded', encounters })
      })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) {
          setEncounterState({ status: 'failed', message: encounterErrorMessage(failure, 'load') })
        }
      })
    return () => controller.abort()
  }, [patientId, initialEncounters])

  function change(field: DiagnosisField, value: string) {
    setFields((current) => ({ ...current, [field]: value, ...(field === 'codeSystem' && !value ? { code: '' } : {}) }))
    if (fieldError?.field === field) setFieldError(null)
  }

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    // One save at a time: a second submit while the first is pending is ignored.
    const result = await guard.current(async () => {
      setPending(true)
      setError('')
      setFieldError(null)
      try {
        return await submitDiagnosis(fields, { create: (request) => createDiagnosis(patientId, request), onCreated })
      } finally {
        setPending(false)
      }
    })
    if (result && 'error' in result) {
      if (result.field) setFieldError({ field: result.field, message: result.error })
      else setError(result.error)
    }
  }

  return (
    <CreateDiagnosisFormView encounterState={encounterState} fields={fields} fieldError={fieldError} pending={pending}
      error={error} today={today} onChange={change} onSubmit={submit} onCancel={onCancel} />
  )
}

export default CreateDiagnosisForm
