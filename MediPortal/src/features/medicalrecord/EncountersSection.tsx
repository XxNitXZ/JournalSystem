import { useEffect, useState } from 'react'
import { CalendarClock, CalendarPlus } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import Badge from '../../components/ui/Badge.tsx'
import Card from '../../components/ui/Card.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import type { ApiRequestOptions } from '../../services/apiClient.ts'
import CreateEncounterForm from './CreateEncounterForm.tsx'
import { encounterErrorMessage, formatPlace, formatVisitDate, formatVisitStatus } from './encounterForm.ts'
import type { EncounterSummary, PractitionerOption } from './types.ts'

export type EncounterListState = { status: 'loading' } | { status: 'failed'; message: string }
  | { status: 'loaded'; encounters: EncounterSummary[] }

const statusTones: Record<string, 'success' | 'warning' | 'neutral'> = {
  finished: 'success',
  'in-progress': 'warning',
  planned: 'neutral',
}

/** The visits, newest first as delivered by MediCore; loading, error and empty states. Exported for tests. */
export function EncounterListView({ state, emptyText }: { state: EncounterListState; emptyText: string }) {
  if (state.status === 'loading') return <LoadingState label="Loading encounters…" />
  if (state.status === 'failed') return <Alert tone="error" role="alert">{state.message}</Alert>
  if (state.encounters.length === 0) return <p className="muted">{emptyText}</p>
  return (
    <div className="table-scroll">
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
          {state.encounters.map((encounter) => (
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
  )
}

interface EncountersSectionProps {
  /** Loads the visits: a patient's (clinicians) or the signed-in patient's own. */
  load: (options: ApiRequestOptions) => Promise<EncounterSummary[]>
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
      .then((encounters) => { if (!controller.signal.aborted) setState({ status: 'loaded', encounters }) })
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
      <EncounterListView state={state} emptyText={emptyText} />
    </Card>
  )
}

export default EncountersSection
