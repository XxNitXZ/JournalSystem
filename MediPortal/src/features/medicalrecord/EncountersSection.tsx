import { useEffect, useState } from 'react'
import { CalendarClock, CalendarPlus } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import Badge from '../../components/ui/Badge.tsx'
import Card from '../../components/ui/Card.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import type { ApiRequestOptions } from '../../services/apiClient.ts'
import CreateEncounterForm from './CreateEncounterForm.tsx'
import { encounterErrorMessage, formatPlace, formatVisitDate, formatVisitStatus } from './encounterForm.ts'
import type { EncounterListResult, PractitionerOption } from './types.ts'

export type EncounterListState = { status: 'loading' } | { status: 'failed'; message: string }
  | ({ status: 'loaded' } & EncounterListResult)

const statusTones: Record<string, 'success' | 'warning' | 'neutral'> = {
  finished: 'success',
  'in-progress': 'warning',
  planned: 'neutral',
}

/** The visits, newest first as delivered by MediCore; loading, error and empty states. Exported for tests. */
export function EncounterListView({ state, emptyText, patientKey }: { state: EncounterListState; emptyText: string; patientKey?: string }) {
  const items = state.status === 'loaded' ? state.encounters : null
  const [display, setDisplay] = useState({ items, patientKey, expanded: false })
  const changed = display.items !== items || display.patientKey !== patientKey
  if (changed) setDisplay({ items, patientKey, expanded: false })
  const expanded = !changed && display.expanded
  if (state.status === 'loading') return <LoadingState label="Loading encounters…" />
  if (state.status === 'failed') return <Alert tone="error" role="alert">{state.message}</Alert>
  const warning = !state.complete && <Alert tone="info" role="alert">This list is incomplete. {state.excludedCount} encounters were excluded because source records are invalid.</Alert>
  if (state.encounters.length === 0) return <>{warning}<p className="muted">{state.complete ? emptyText : 'No valid encounters could be displayed.'}</p></>
  return (
    <>{warning}<div className="table-scroll">
      <table className="data-table">
        <caption className="visually-hidden">Encounters, most recent first</caption>
        <thead>
          <tr>
            <th scope="col">Date</th>
            <th scope="col">Practitioner</th>
            <th scope="col">Type</th>
            <th scope="col">Status</th>
            <th scope="col">Place</th>
          </tr>
        </thead>
        <tbody>
          {(expanded ? state.encounters : state.encounters.slice(0, 10)).map((encounter) => (
            <tr key={encounter.id}>
              <td className="data-table__date">{formatVisitDate(encounter.start)}</td>
              <td>{encounter.practitioners.length ? encounter.practitioners.join(', ') : 'Not recorded'}</td>
              <td>{encounter.type ?? 'Not recorded'}</td>
              <td>
                <Badge tone={statusTones[encounter.status] ?? 'neutral'}>{formatVisitStatus(encounter.status)}</Badge>
              </td>
              <td>{formatPlace(encounter.organization, encounter.location) ?? '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
      {state.encounters.length > 10 && <button type="button" className="btn btn--ghost btn--sm"
        aria-expanded={expanded} onClick={() => setDisplay({ items, patientKey, expanded: !expanded })}>
        {expanded ? 'Show Less' : 'Show All'}
      </button>}
    </>
  )
}

interface EncountersSectionProps {
  /** Loads the visits: a patient's (clinicians) or the signed-in patient's own. */
  load: (options: ApiRequestOptions) => Promise<EncounterListResult>
  /** The open patient for Create Encounter; only for doctors and staff. */
  createFor?: string
  emptyText?: string
  /** For tests: the list state and the open form. */
  initialState?: EncounterListState
  initiallyCreating?: boolean
  initialPractitioners?: PractitionerOption[]
}

/**
 * The Encounters section of a patient record. Doctors and staff see "Create Encounter" (only when createFor is
 * given); patients only read. After a visit is created the list is loaded again.
 */
function EncountersSection({ load, createFor, emptyText = 'No encounters have been recorded yet.', initialState,
  initiallyCreating = false, initialPractitioners }: EncountersSectionProps) {
  const [state, setState] = useState<EncounterListState>(initialState ?? { status: 'loading' })
  const [reload, setReload] = useState(0)
  const [creating, setCreating] = useState(initiallyCreating)
  const [success, setSuccess] = useState('')

  useEffect(() => {
    if (initialState && reload === 0) return undefined
    const controller = new AbortController()
    load({ signal: controller.signal })
      .then((result) => { if (!controller.signal.aborted) setState({ status: 'loaded', ...result }) })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'failed', message: encounterErrorMessage(failure, 'load') })
      })
    return () => controller.abort()
  }, [load, reload, initialState])

  return (
    <Card title="Encounters" titleId="encounters-title" icon={CalendarClock} className="card--wide">
      {createFor && !creating && (
        <div className="card__actions card__actions--top">
          <button type="button" className="btn btn--secondary btn--sm" onClick={() => { setSuccess(''); setCreating(true) }}>
            <CalendarPlus size={16} aria-hidden="true" />Create Encounter
          </button>
        </div>
      )}
      {createFor && creating && (
        <CreateEncounterForm patientId={createFor} initialPractitioners={initialPractitioners}
          onCancel={() => setCreating(false)}
          onCreated={() => {
            setCreating(false)
            setSuccess('The encounter has been created.')
            setReload((value) => value + 1)
          }} />
      )}
      <Alert tone="success" role="status">{success}</Alert>
      <EncounterListView state={state} emptyText={emptyText} patientKey={createFor} />
    </Card>
  )
}

export default EncountersSection
