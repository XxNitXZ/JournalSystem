import { useEffect, useRef, useState, type SubmitEvent } from 'react'
import { CalendarPlus, X } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import { encounterErrorMessage, submitEncounter, toLocalInputValue } from './encounterForm.ts'
import { createEncounter, listPractitioners } from './encounterService.ts'
import {
  newVisitStatusLabels,
  newVisitStatuses,
  visitTypeLabels,
  visitTypes,
  type EncounterSummary,
  type PractitionerOption,
} from './types.ts'

export type PractitionerState = { status: 'loading' } | { status: 'failed'; message: string }
  | { status: 'loaded'; practitioners: PractitionerOption[] }

interface CreateEncounterFormViewProps {
  practitionerState: PractitionerState
  /** Prefilled date and time (datetime-local value). */
  defaultVisitTime: string
  pending: boolean
  error: string
  onSubmit: (event: SubmitEvent<HTMLFormElement>) => void
  onCancel: () => void
}

/**
 * The Create Encounter form. The patient is the one whose record is open; it is not a field. Practitioners are chosen
 * by name (the clinician's own is preselected). Exported for rendering tests.
 */
export function CreateEncounterFormView({ practitionerState, defaultVisitTime, pending, error, onSubmit, onCancel }:
  CreateEncounterFormViewProps) {
  const practitioners = practitionerState.status === 'loaded' ? practitionerState.practitioners : []
  const preselected = practitioners.find((practitioner) => practitioner.own)?.id ?? ''
  const noPractitioners = practitionerState.status === 'loaded' && practitioners.length === 0
  return (
    <form className="form encounter-form" onSubmit={onSubmit} aria-busy={pending} aria-labelledby="create-encounter-title"
      noValidate>
      <h3 id="create-encounter-title">Create Encounter</h3>
      <fieldset disabled={pending || practitionerState.status !== 'loaded' || noPractitioners}>
        <legend className="visually-hidden">Visit details</legend>
        <div className="form__row">
          <FormField htmlFor="encounter-time" label="Date and time" hintId="encounter-time-help"
            hint="Local time of the visit.">
            <input id="encounter-time" name="visitTime" type="datetime-local" defaultValue={defaultVisitTime}
              aria-describedby="encounter-time-help" required />
          </FormField>
          <FormField htmlFor="encounter-practitioner" label="Practitioner">
            <select id="encounter-practitioner" name="practitionerId" defaultValue={preselected} required>
              <option value="" disabled>Select a practitioner</option>
              {practitioners.map((practitioner) => (
                <option key={practitioner.id} value={practitioner.id}>
                  {practitioner.own ? `${practitioner.name} (you)` : practitioner.name}
                </option>
              ))}
            </select>
          </FormField>
        </div>
        <div className="form__row">
          <FormField htmlFor="encounter-type" label="Visit type">
            <select id="encounter-type" name="type" defaultValue="CONSULTATION" required>
              {visitTypes.map((type) => <option key={type} value={type}>{visitTypeLabels[type]}</option>)}
            </select>
          </FormField>
          <FormField htmlFor="encounter-status" label="Status">
            <select id="encounter-status" name="status" defaultValue="FINISHED" required>
              {newVisitStatuses.map((status) => <option key={status} value={status}>{newVisitStatusLabels[status]}</option>)}
            </select>
          </FormField>
        </div>
        <div className="page-actions">
          <button className="btn btn--primary" type="submit">
            <CalendarPlus size={18} aria-hidden="true" />
            {pending ? 'Creating encounter…' : 'Create Encounter'}
          </button>
          <button className="btn btn--secondary" type="button" onClick={onCancel}>
            <X size={18} aria-hidden="true" />Cancel
          </button>
        </div>
      </fieldset>
      {practitionerState.status === 'loading' && <LoadingState label="Loading practitioners…" />}
      {practitionerState.status === 'failed' && <Alert tone="error" role="alert">{practitionerState.message}</Alert>}
      {noPractitioners && (
        <Alert tone="info">
          No practitioners are available yet. A practitioner must be linked to a doctor or staff account before visits
          can be documented.
        </Alert>
      )}
      <Alert tone="error" role="alert">{error}</Alert>
    </form>
  )
}

interface CreateEncounterFormProps {
  patientId: string
  onCreated: (created: EncounterSummary) => void
  onCancel: () => void
  /** Practitioners already known (for tests); otherwise they are loaded from MediCore. */
  initialPractitioners?: PractitionerOption[]
}

/** Documents a visit for the open patient (DOCTOR and STAFF). */
function CreateEncounterForm({ patientId, onCreated, onCancel, initialPractitioners }: CreateEncounterFormProps) {
  const [practitionerState, setPractitionerState] = useState<PractitionerState>(
    initialPractitioners ? { status: 'loaded', practitioners: initialPractitioners } : { status: 'loading' })
  const [defaultVisitTime] = useState(() => toLocalInputValue(new Date()))
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const submitting = useRef(false)

  useEffect(() => {
    if (initialPractitioners) return undefined
    const controller = new AbortController()
    listPractitioners({ signal: controller.signal })
      .then((practitioners) => {
        if (!controller.signal.aborted) setPractitionerState({ status: 'loaded', practitioners })
      })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) {
          setPractitionerState({ status: 'failed', message: encounterErrorMessage(failure, 'load') })
        }
      })
    return () => controller.abort()
  }, [initialPractitioners])

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const data = new FormData(event.currentTarget)
    submitting.current = true
    setPending(true)
    setError('')
    const result = await submitEncounter({
      visitTime: String(data.get('visitTime') ?? ''),
      practitionerId: String(data.get('practitionerId') ?? ''),
      type: String(data.get('type') ?? ''),
      status: String(data.get('status') ?? ''),
    }, { create: (request) => createEncounter(patientId, request), onCreated })
    submitting.current = false
    setPending(false)
    if ('error' in result) setError(result.error)
  }

  return (
    <CreateEncounterFormView practitionerState={practitionerState} defaultVisitTime={defaultVisitTime}
      pending={pending} error={error} onSubmit={submit} onCancel={onCancel} />
  )
}

export default CreateEncounterForm
