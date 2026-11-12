import { useEffect, useState } from 'react'
import { ClipboardPlus, HeartPulse, RefreshCw } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import Badge from '../../components/ui/Badge.tsx'
import Card from '../../components/ui/Card.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import type { ApiRequestOptions } from '../../services/apiClient.ts'
import CreateDiagnosisForm from './CreateDiagnosisForm.tsx'
import { formatEncounterLabel } from './clinicalNoteForm.ts'
import {
  diagnosisErrorMessage,
  diagnosisTitle,
  formatCategory,
  formatClinicalStatus,
  formatDiagnosisCode,
  formatVerificationStatus,
} from './diagnosisForm.ts'
import { formatVisitDate } from './encounterForm.ts'
import type { Diagnosis, EncounterSummary } from './types.ts'

export type DiagnosisListState = { status: 'loading' } | { status: 'failed'; message: string }
  | { status: 'loaded'; diagnoses: Diagnosis[] }

const clinicalTones: Record<string, 'success' | 'warning' | 'neutral' | 'danger'> = {
  active: 'danger', recurrence: 'danger', relapse: 'danger', remission: 'warning', inactive: 'neutral',
  resolved: 'success',
}

/**
 * The diagnoses, newest first as delivered by MediCore: diagnosis, code, statuses, dates, encounter and recorder.
 * Values that a Condition recorded elsewhere does not have are left out or shown as "Not recorded". Exported for tests.
 */
export function DiagnosisListView({ state, emptyText, patientKey }: { state: DiagnosisListState; emptyText: string; patientKey?: string }) {
  const items = state.status === 'loaded' ? state.diagnoses : null
  const [display, setDisplay] = useState({ items, patientKey, expanded: false })
  const changed = display.items !== items || display.patientKey !== patientKey
  if (changed) setDisplay({ items, patientKey, expanded: false })
  const expanded = !changed && display.expanded
  if (state.status === 'loading') return <LoadingState label="Loading diagnoses…" />
  if (state.status === 'failed') return <Alert tone="error" role="alert">{state.message}</Alert>
  if (state.diagnoses.length === 0) return <p className="muted">{emptyText}</p>
  return (
    <><ul className="note-list" aria-label="Diagnoses, most recently recorded first">
      {(expanded ? state.diagnoses : state.diagnoses.slice(0, 10)).map((diagnosis) => {
        const clinical = formatClinicalStatus(diagnosis.clinicalStatus)
        const verification = formatVerificationStatus(diagnosis.verificationStatus)
        const code = diagnosis.diagnosis ? formatDiagnosisCode(diagnosis.code) : null
        const category = formatCategory(diagnosis.category)
        return (
          <li key={diagnosis.id} className="note-card">
            <div className="note-card__meta">
              <span className="diagnosis-card__name">{diagnosisTitle(diagnosis)}</span>
              {clinical && <Badge tone={clinicalTones[diagnosis.clinicalStatus ?? ''] ?? 'neutral'}>{clinical}</Badge>}
              {verification && <Badge tone="primary">{verification}</Badge>}
            </div>
            <dl className="import-card__details">
              {code && <div><dt>Code</dt><dd>{code}</dd></div>}
              <div><dt>Recorded</dt><dd>{diagnosis.recordedDate ? formatVisitDate(diagnosis.recordedDate) : 'Not recorded'}</dd></div>
              {diagnosis.onset && <div><dt>Onset</dt><dd>{diagnosis.onset.includes('T') ? formatVisitDate(diagnosis.onset) : diagnosis.onset}</dd></div>}
              {category && <div><dt>Category</dt><dd>{category}</dd></div>}
              {diagnosis.recorder && <div><dt>Recorded by</dt><dd>{diagnosis.recorder}</dd></div>}
            </dl>
            {diagnosis.encounter && (
              <p className="note-card__visit">Encounter: {formatEncounterLabel(diagnosis.encounter)}</p>
            )}
          </li>
        )
      })}
    </ul>
      {state.diagnoses.length > 10 && <button type="button" className="btn btn--ghost btn--sm"
        aria-expanded={expanded} onClick={() => setDisplay({ items, patientKey, expanded: !expanded })}>
        {expanded ? 'Show Less' : 'Show All'}
      </button>}
    </>
  )
}

interface DiagnosesSectionProps {
  /** Loads the diagnoses: a patient's (clinicians) or the signed-in patient's own. */
  load: (options: ApiRequestOptions) => Promise<Diagnosis[]>
  /** The open patient for Add Diagnosis; only for doctors and staff. */
  createFor?: string
  emptyText?: string
  /** For tests: the list state, the open form and its encounters. */
  initialState?: DiagnosisListState
  initiallyCreating?: boolean
  initialEncounters?: EncounterSummary[]
}

/**
 * The Diagnoses section of a patient record. Doctors and staff see "Add Diagnosis" and "Refresh" (only when
 * createFor is given); patients only read. There is no edit or delete. After a diagnosis is saved the list is
 * loaded again.
 */
function DiagnosesSection({ load, createFor, emptyText = 'No diagnoses have been recorded yet.', initialState,
  initiallyCreating = false, initialEncounters }: DiagnosesSectionProps) {
  const [state, setState] = useState<DiagnosisListState>(initialState ?? { status: 'loading' })
  const [reload, setReload] = useState(0)
  const [creating, setCreating] = useState(initiallyCreating)
  const [success, setSuccess] = useState('')

  useEffect(() => {
    if (initialState && reload === 0) return undefined
    const controller = new AbortController()
    load({ signal: controller.signal })
      .then((diagnoses) => { if (!controller.signal.aborted) setState({ status: 'loaded', diagnoses }) })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'failed', message: diagnosisErrorMessage(failure, 'load') })
      })
    return () => controller.abort()
  }, [load, reload, initialState])

  function refresh() {
    setSuccess('')
    setState({ status: 'loading' })
    setReload((value) => value + 1)
  }

  return (
    <Card title="Diagnoses" titleId="diagnoses-title" icon={HeartPulse} className="card--wide">
      {createFor && (
        <div className="card__actions card__actions--top">
          {!creating && (
            <button type="button" className="btn btn--secondary btn--sm" onClick={() => { setSuccess(''); setCreating(true) }}>
              <ClipboardPlus size={16} aria-hidden="true" />Add Diagnosis
            </button>
          )}
          <button type="button" className="btn btn--ghost btn--sm" onClick={refresh} disabled={state.status === 'loading'}>
            <RefreshCw size={16} aria-hidden="true" />Refresh
          </button>
        </div>
      )}
      {createFor && creating && (
        <CreateDiagnosisForm patientId={createFor} initialEncounters={initialEncounters}
          onCancel={() => setCreating(false)}
          onCreated={() => {
            setCreating(false)
            setSuccess('The diagnosis has been recorded.')
            setReload((value) => value + 1)
          }} />
      )}
      <Alert tone="success" role="status">{success}</Alert>
      <DiagnosisListView state={state} emptyText={emptyText} patientKey={createFor} />
    </Card>
  )
}

export default DiagnosesSection
