import { useEffect, useState } from 'react'
import { Activity } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import Card from '../../components/ui/Card.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import type { ApiRequestOptions } from '../../services/apiClient.ts'
import CreateObservationForm from './CreateObservationForm.tsx'
import { observationConcept, observationError, observationValue } from './observationForm.ts'
import type { ObservationSearchResult, ObservationComponent } from './types.ts'

export type ObservationListState = { status: 'loading' } | { status: 'failed'; message: string } | ({ status: 'loaded' } & ObservationSearchResult)
function result(item: ObservationComponent): string {
  return item.value ? observationValue(item.value) : item.dataAbsentReason ? `Not measured: ${observationConcept(item.dataAbsentReason)}` : 'Not recorded'
}
export function ObservationListView({ state }: { state: ObservationListState }) {
  if (state.status === 'loading') return <LoadingState label="Loading observations…" />
  if (state.status === 'failed') return <Alert tone="error" role="alert">{state.message}</Alert>
  const warning = !state.complete && <Alert tone="info" role="alert">
    This list is incomplete. {state.excludedCount} {state.excludedCount === 1 ? 'observation was' : 'observations were'} excluded because source records are invalid.
  </Alert>
  if (!state.observations.length) return <>{warning}<p className="muted">{state.complete
    ? 'No observations have been recorded yet.' : 'No valid observations could be displayed.'}</p></>
  return <>{warning}<ul className="note-list" aria-label="Observations">{state.observations.map((item) => <li className="note-card" key={item.id}>
    <h3>{observationConcept(item.code)}</h3>
    <dl className="import-card__details">
      <div><dt>Status</dt><dd>{item.status}</dd></div>
      <div><dt>Effective date</dt><dd>{observationValue(item.effective)}</dd></div>
      <div><dt>Result</dt><dd>{result(item)}</dd></div>
      {item.issued && <div><dt>Issued</dt><dd>{item.issued}</dd></div>}
    </dl>
    {item.components.length > 0 && <ul aria-label="Observation components">{item.components.map((component, index) =>
      <li key={index}>{observationConcept(component.code)}: {result(component)}</li>)}</ul>}
  </li>)}</ul></>
}

export default function ObservationsSection({ load, createFor, initialState, initiallyCreating = false }: {
  load: (options: ApiRequestOptions) => Promise<ObservationSearchResult>; createFor?: string
  initialState?: ObservationListState; initiallyCreating?: boolean
}) {
  const [state, setState] = useState<ObservationListState>(initialState ?? { status: 'loading' })
  const [reload, setReload] = useState(0)
  const [creating, setCreating] = useState(initiallyCreating)
  const [success, setSuccess] = useState('')
  useEffect(() => {
    if (initialState && reload === 0) return
    const controller = new AbortController()
    load({ signal: controller.signal })
      .then((result) => { if (!controller.signal.aborted) setState({ status: 'loaded', ...result }) })
      .catch((error: unknown) => { if (!controller.signal.aborted) setState({ status: 'failed', message: observationError(error) }) })
    return () => controller.abort()
  }, [load, reload, initialState])
  function refresh() { setState({ status: 'loading' }); setReload((value) => value + 1) }
  return <Card title="Observations" titleId="observations-title" icon={Activity} className="card--wide">
    {createFor && <div className="card__actions card__actions--top">
      {!creating && <button type="button" className="btn btn--secondary btn--sm" onClick={() => { setSuccess(''); setCreating(true) }}>Add Observation</button>}
      <button type="button" className="btn btn--ghost btn--sm" disabled={state.status === 'loading'} onClick={refresh}>Refresh</button>
    </div>}
    {createFor && creating && <CreateObservationForm key={createFor} patientId={createFor} onCancel={() => setCreating(false)}
      onCreated={() => { setCreating(false); setSuccess('The observation was recorded.'); refresh() }} />}
    <Alert tone="success" role="status">{success}</Alert>
    {success && state.status === 'failed' && <Alert tone="info" role="status">The observation was saved, but the list could not be refreshed. Do not record it again.</Alert>}
    <ObservationListView state={state} />
  </Card>
}
