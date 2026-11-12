import { useEffect, useState } from 'react'
import { FilePlus, NotebookText, RefreshCw } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import Badge from '../../components/ui/Badge.tsx'
import Card from '../../components/ui/Card.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import type { ApiRequestOptions } from '../../services/apiClient.ts'
import CreateClinicalNoteForm from './CreateClinicalNoteForm.tsx'
import { clinicalNoteErrorMessage, formatEncounterLabel, formatNoteDate, noteStatusLabel } from './clinicalNoteForm.ts'
import type { ClinicalNote, EncounterSummary } from './types.ts'

export type NoteListState = { status: 'loading' } | { status: 'failed'; message: string }
  | { status: 'loaded'; notes: ClinicalNote[] }

/**
 * The notes, newest first as delivered by MediCore, with date, author, visit and text. The text is rendered as plain
 * text (React escapes it; line breaks are kept by CSS), never as HTML or Markdown. Exported for tests.
 */
export function ClinicalNoteListView({ state, emptyText }: { state: NoteListState; emptyText: string }) {
  if (state.status === 'loading') return <LoadingState label="Loading clinical notes…" />
  if (state.status === 'failed') return <Alert tone="error" role="alert">{state.message}</Alert>
  if (state.notes.length === 0) return <p className="muted">{emptyText}</p>
  return (
    <ol className="note-list" aria-label="Clinical notes, most recent first">
      {state.notes.map((note) => {
        const status = noteStatusLabel(note.status)
        return (
          <li key={note.id} className="note-card">
            <div className="note-card__meta">
              <span className="note-card__date">{formatNoteDate(note.createdAt)}</span>
              <span>{note.author ?? 'Author not recorded'}</span>
              {status && <Badge tone="warning">{status}</Badge>}
            </div>
            <p className="note-card__visit">
              {note.encounter ? `Encounter: ${formatEncounterLabel(note.encounter)}` : 'Encounter details not available'}
            </p>
            {note.contentAvailable && note.content !== null
              ? <p className="note-card__text">{note.content}</p>
              : <p className="note-card__text note-card__text--unavailable">This note is stored in a format that MediCore cannot display.</p>}
          </li>
        )
      })}
    </ol>
  )
}

interface ClinicalNotesSectionProps {
  /** Loads the notes: a patient's (clinicians) or the signed-in patient's own. */
  load: (options: ApiRequestOptions) => Promise<ClinicalNote[]>
  /** The open patient for Add Clinical Note; only for doctors and staff. */
  createFor?: string
  emptyText?: string
  /** For tests: the list state, the open form and its encounters. */
  initialState?: NoteListState
  initiallyCreating?: boolean
  initialEncounters?: EncounterSummary[]
}

/**
 * The Clinical Notes section of a patient record. Doctors and staff see "Add Clinical Note" and "Refresh" (only when
 * createFor is given); patients only read. After a note is saved the notes are loaded again.
 */
function ClinicalNotesSection({ load, createFor, emptyText = 'No clinical notes have been written yet.', initialState,
  initiallyCreating = false, initialEncounters }: ClinicalNotesSectionProps) {
  const [state, setState] = useState<NoteListState>(initialState ?? { status: 'loading' })
  const [reload, setReload] = useState(0)
  const [creating, setCreating] = useState(initiallyCreating)
  const [success, setSuccess] = useState('')

  useEffect(() => {
    if (initialState && reload === 0) return undefined
    const controller = new AbortController()
    load({ signal: controller.signal })
      .then((notes) => { if (!controller.signal.aborted) setState({ status: 'loaded', notes }) })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'failed', message: clinicalNoteErrorMessage(failure, 'load') })
      })
    return () => controller.abort()
  }, [load, reload, initialState])

  function refresh() {
    setSuccess('')
    setState({ status: 'loading' })
    setReload((value) => value + 1)
  }

  return (
    <Card title="Clinical Notes" titleId="clinical-notes-title" icon={NotebookText} className="card--wide">
      {createFor && (
        <div className="card__actions card__actions--top">
          {!creating && (
            <button type="button" className="btn btn--secondary btn--sm" onClick={() => { setSuccess(''); setCreating(true) }}>
              <FilePlus size={16} aria-hidden="true" />Add Clinical Note
            </button>
          )}
          <button type="button" className="btn btn--ghost btn--sm" onClick={refresh} disabled={state.status === 'loading'}>
            <RefreshCw size={16} aria-hidden="true" />Refresh
          </button>
        </div>
      )}
      {createFor && creating && (
        <CreateClinicalNoteForm patientId={createFor} initialEncounters={initialEncounters}
          onCancel={() => setCreating(false)}
          onCreated={() => {
            setCreating(false)
            setSuccess('The clinical note has been saved.')
            setReload((value) => value + 1)
          }} />
      )}
      <Alert tone="success" role="status">{success}</Alert>
      <ClinicalNoteListView state={state} emptyText={emptyText} />
    </Card>
  )
}

export default ClinicalNotesSection
