import { useEffect, useRef, useState, type SubmitEvent } from 'react'
import { FilePlus, X } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import { createClinicalNote } from './clinicalNoteService.ts'
import {
  MAX_NOTE_LENGTH,
  createSubmitGuard,
  formatEncounterLabel,
  noteLength,
  submitClinicalNote,
} from './clinicalNoteForm.ts'
import { encounterErrorMessage } from './encounterForm.ts'
import { listPatientEncounters } from './encounterService.ts'
import type { ClinicalNote, EncounterListResult, EncounterSummary } from './types.ts'

export type NoteEncounterState = { status: 'loading' } | { status: 'failed'; message: string }
  | ({ status: 'loaded' } & EncounterListResult)

interface CreateClinicalNoteFormViewProps {
  encounterState: NoteEncounterState
  encounterId: string
  content: string
  pending: boolean
  error: string
  onEncounterChange: (encounterId: string) => void
  onContentChange: (content: string) => void
  onSubmit: (event: SubmitEvent<HTMLFormElement>) => void
  onCancel: () => void
}

/**
 * The Create Clinical Note form. The patient is the one whose record is open and the author is the signed-in
 * clinician; neither is a field. The visit is chosen by date and type. Exported for rendering tests.
 */
export function CreateClinicalNoteFormView({ encounterState, encounterId, content, pending, error, onEncounterChange,
  onContentChange, onSubmit, onCancel }: CreateClinicalNoteFormViewProps) {
  const encounters = encounterState.status === 'loaded' ? encounterState.encounters : []
  const noEncounters = encounterState.status === 'loaded' && encounters.length === 0
  const selected = encounters.find((encounter) => encounter.id === encounterId)
  const length = noteLength(content)
  return (
    <form className="form encounter-form" onSubmit={onSubmit} aria-busy={pending}
      aria-labelledby="create-note-title" noValidate>
      <h3 id="create-note-title">Add Clinical Note</h3>
      <fieldset disabled={pending || encounterState.status !== 'loaded' || noEncounters}>
        <legend className="visually-hidden">Clinical note</legend>
        <FormField htmlFor="note-encounter" label="Encounter" hintId="note-encounter-help"
          hint={selected ? `Selected visit: ${formatEncounterLabel(selected)}` : 'The visit this note documents.'}>
          <select id="note-encounter" name="encounterId" value={encounterId} aria-describedby="note-encounter-help"
            onChange={(event) => onEncounterChange(event.target.value)} required>
            <option value="" disabled>Select an encounter</option>
            {encounters.map((encounter) => (
              <option key={encounter.id} value={encounter.id}>{formatEncounterLabel(encounter)}</option>
            ))}
          </select>
        </FormField>
        <FormField htmlFor="note-content" label="Note" hintId="note-length"
          hint={`${length.toLocaleString('en-US')} / ${MAX_NOTE_LENGTH.toLocaleString('en-US')} characters`}>
          <textarea id="note-content" name="content" rows={8} value={content} required aria-describedby="note-length"
            aria-invalid={length > MAX_NOTE_LENGTH || undefined}
            onChange={(event) => onContentChange(event.target.value)} />
        </FormField>
        <div className="page-actions">
          <button className="btn btn--primary" type="submit">
            <FilePlus size={18} aria-hidden="true" />
            {pending ? 'Saving note…' : 'Save note'}
          </button>
          <button className="btn btn--secondary" type="button" onClick={onCancel}>
            <X size={18} aria-hidden="true" />Cancel
          </button>
        </div>
      </fieldset>
      {encounterState.status === 'loading' && <LoadingState label="Loading encounters…" />}
      {encounterState.status === 'loaded' && !encounterState.complete && <Alert tone="info" role="alert">
        The encounter list is incomplete. {encounterState.excludedCount} encounters were excluded because source records are invalid.
        {encounterState.encounters.length === 0 && ' No valid encounters are available.'}
      </Alert>}
      {encounterState.status === 'failed' && <Alert tone="error" role="alert">{encounterState.message}</Alert>}
      {noEncounters && encounterState.status === 'loaded' && encounterState.complete && (
        <Alert tone="info">
          This patient has no encounters yet. A note always belongs to an encounter: use Create Encounter first, then
          add the note.
        </Alert>
      )}
      <Alert tone="error" role="alert">{error}</Alert>
    </form>
  )
}

interface CreateClinicalNoteFormProps {
  patientId: string
  onCreated: (created: ClinicalNote) => void
  onCancel: () => void
  /** Encounters already known (for tests); otherwise they are loaded from MediCore. */
  initialEncounters?: EncounterSummary[]
}

/** Writes a clinical note on one of the open patient's encounters (DOCTOR and STAFF). */
function CreateClinicalNoteForm({ patientId, onCreated, onCancel, initialEncounters }: CreateClinicalNoteFormProps) {
  const [encounterState, setEncounterState] = useState<NoteEncounterState>(
    initialEncounters ? { status: 'loaded', encounters: initialEncounters, complete: true, excludedCount: 0 } : { status: 'loading' })
  const [encounterId, setEncounterId] = useState('')
  const [content, setContent] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const guard = useRef(createSubmitGuard())

  useEffect(() => {
    if (initialEncounters) return undefined
    const controller = new AbortController()
    listPatientEncounters(patientId, { signal: controller.signal })
      .then((result) => {
        if (!controller.signal.aborted) setEncounterState({ status: 'loaded', ...result })
      })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) {
          setEncounterState({ status: 'failed', message: encounterErrorMessage(failure, 'load') })
        }
      })
    return () => controller.abort()
  }, [patientId, initialEncounters])

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    // One save at a time: a second submit while the first is pending is ignored.
    const result = await guard.current(async () => {
      setPending(true)
      setError('')
      try {
        return await submitClinicalNote({ encounterId, content },
          { create: (request) => createClinicalNote(patientId, request), onCreated })
      } finally {
        setPending(false)
      }
    })
    if (result && 'error' in result) setError(result.error)
  }

  return (
    <CreateClinicalNoteFormView encounterState={encounterState} encounterId={encounterId} content={content}
      pending={pending} error={error} onEncounterChange={setEncounterId} onContentChange={setContent}
      onSubmit={submit} onCancel={onCancel} />
  )
}

export default CreateClinicalNoteForm
