import { useRef, useState, type SubmitEvent } from 'react'
import { ArrowRight, ChevronLeft, ChevronRight, Download, Search } from 'lucide-react'
import { Link } from 'react-router'
import Alert from '../../components/ui/Alert.tsx'
import Badge from '../../components/ui/Badge.tsx'
import Card from '../../components/ui/Card.tsx'
import FormField from '../../components/ui/FormField.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { ApiError } from '../../services/apiClient.ts'
import {
  discoverPatients,
  formatCandidateGender,
  formatCandidatePersonnummer,
  importErrorMessage,
  importPatient,
  importSearchErrorMessage,
  importSearchModeLabels,
  importSearchModes,
  importSearchRequest,
  pageOf,
  personnummerNote,
  type PatientCandidate,
} from './patientImport.ts'

export type ImportSearchState = { status: 'idle' } | { status: 'searching' } | { status: 'failed'; message: string }
  | { status: 'loaded'; patients: PatientCandidate[]; truncated: boolean }

interface ImportResultsViewProps {
  state: ImportSearchState
  page: number
  /** ID of the patient being imported, if any. */
  importing: string | null
  onImport: (patient: PatientCandidate) => void
  onPage: (page: number) => void
}

/**
 * Search results: one card per patient (several with the same name stay separate), with name, personnummer, gender
 * and birth date, and either "Import Patient" or "Open profile" when the patient is already in MediCore. Record IDs
 * are not shown. Exported for rendering tests.
 */
export function ImportResultsView({ state, page, importing, onImport, onPage }: ImportResultsViewProps) {
  if (state.status === 'idle') return null
  if (state.status === 'searching') return <LoadingState label="Searching the patient records server…" />
  if (state.status === 'failed') return <Alert tone="error" role="alert">{state.message}</Alert>
  if (state.patients.length === 0) return <p className="muted">No patients were found.</p>
  const current = pageOf(state.patients, page)
  const first = (current.page - 1) * 10 + 1
  return (
    <div className="import-results">
      <p className="search-results__summary" role="status">
        {state.patients.length === 1 ? '1 patient found' : `${state.patients.length} patients found`}
        {current.pages > 1 && ` · showing ${first}–${first + current.items.length - 1}`}
      </p>
      {state.truncated && (
        <Alert tone="info">Only the first 100 matches are shown. Refine the search to find a specific patient.</Alert>
      )}
      <ul className="import-results__list" aria-label="Patients on the records server">
        {current.items.map((patient) => {
          const name = patient.name ?? 'Name not recorded'
          return (
            <li key={patient.id} className="import-card">
              <div className="import-card__identity">
                <span className="import-card__name">{name}</span>
                <dl className="import-card__details">
                  <div><dt>Personnummer</dt><dd>{formatCandidatePersonnummer(patient.personnummer, patient.personnummerStatus)}</dd></div>
                  <div><dt>Gender</dt><dd>{formatCandidateGender(patient.gender)}</dd></div>
                  <div><dt>Born</dt><dd>{patient.birthDate ?? 'Not recorded'}</dd></div>
                </dl>
                {patient.importable && !patient.registered && personnummerNote(patient) && (
                  <p className="import-card__note">{personnummerNote(patient)}</p>
                )}
              </div>
              <div className="import-card__actions">
                {patient.registered ? (
                  <>
                    <Badge tone="success">In MediCore</Badge>
                    <Link to={`/patients/${encodeURIComponent(patient.id)}`} className="btn btn--secondary btn--sm"
                      aria-label={`Open profile for ${name}`}>
                      Open profile<ArrowRight size={16} aria-hidden="true" />
                    </Link>
                  </>
                ) : patient.importable ? (
                  <button type="button" className="btn btn--primary btn--sm" disabled={importing !== null}
                    onClick={() => onImport(patient)} aria-label={`Import ${name}`}>
                    <Download size={16} aria-hidden="true" />
                    {importing === patient.id ? 'Importing…' : 'Import Patient'}
                  </button>
                ) : (
                  <Badge tone="warning">Cannot be imported</Badge>
                )}
              </div>
            </li>
          )
        })}
      </ul>
      {current.pages > 1 && (
        <nav className="pager" aria-label="Result pages">
          <button type="button" className="btn btn--secondary btn--sm" disabled={current.page === 1}
            onClick={() => onPage(current.page - 1)}>
            <ChevronLeft size={16} aria-hidden="true" />Previous
          </button>
          <span>Page {current.page} of {current.pages}</span>
          <button type="button" className="btn btn--secondary btn--sm" disabled={current.page === current.pages}
            onClick={() => onPage(current.page + 1)}>
            Next<ChevronRight size={16} aria-hidden="true" />
          </button>
        </nav>
      )}
    </div>
  )
}

type ImportOutcome = { tone: 'success' | 'error' | 'info'; message: string; patientId?: string } | null

/**
 * Import Existing Patient (DOCTOR and STAFF): find a patient that already exists on the patient records server and
 * add it to MediCore by reference. The record itself is reused; nothing is created or changed on the server.
 */
function ImportPatientPage() {
  const [state, setState] = useState<ImportSearchState>({ status: 'idle' })
  const [page, setPage] = useState(1)
  const [importing, setImporting] = useState<string | null>(null)
  const [outcome, setOutcome] = useState<ImportOutcome>(null)
  const [error, setError] = useState('')
  const busy = useRef(false)

  async function search(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy.current) return
    const data = new FormData(event.currentTarget)
    const checked = importSearchRequest(String(data.get('mode') ?? ''), String(data.get('value') ?? ''))
    if ('error' in checked) {
      setError(checked.error)
      return
    }
    setError('')
    setOutcome(null)
    busy.current = true
    setState({ status: 'searching' })
    try {
      const found = await discoverPatients(checked.mode, checked.value)
      setState({ status: 'loaded', patients: found.patients, truncated: found.truncated })
      setPage(1)
    } catch (failure: unknown) {
      setState({ status: 'failed', message: importSearchErrorMessage(failure) })
    } finally {
      busy.current = false
    }
  }

  function markRegistered(id: string) {
    setState((current) => current.status !== 'loaded' ? current : {
      ...current,
      patients: current.patients.map((patient) => patient.id === id ? { ...patient, registered: true } : patient),
    })
  }

  async function importOne(patient: PatientCandidate) {
    if (busy.current) return
    busy.current = true
    setImporting(patient.id)
    setOutcome(null)
    try {
      await importPatient(patient.id)
      markRegistered(patient.id)
      setOutcome({ tone: 'success', message: 'The patient has been imported into MediCore.', patientId: patient.id })
    } catch (failure: unknown) {
      const duplicate = failure instanceof ApiError && failure.status === 409
      if (duplicate) markRegistered(patient.id)
      setOutcome({ tone: duplicate ? 'info' : 'error', message: importErrorMessage(failure),
        patientId: duplicate ? patient.id : undefined })
    } finally {
      busy.current = false
      setImporting(null)
    }
  }

  return (
    <section className="page" aria-labelledby="import-patient-title">
      <PageHeader titleId="import-patient-title" eyebrow="Patient management" title="Import existing patient"
        icon={Download}
        description="Find a patient who already exists on the patient records server and add them to MediCore. The record is reused, not copied or changed." />
      <Card title="Search the patient records server" titleId="import-search-title" icon={Search} className="card--wide">
        <form className="form" onSubmit={search} aria-busy={state.status === 'searching'} noValidate>
          <div className="form__row">
            <FormField htmlFor="import-mode" label="Search by">
              <select id="import-mode" name="mode" defaultValue="NAME">
                {importSearchModes.map((mode) => <option key={mode} value={mode}>{importSearchModeLabels[mode]}</option>)}
              </select>
            </FormField>
            <FormField htmlFor="import-value" label="Search" hintId="import-value-help"
              hint="A name, a personnummer (YYYYMMDD-NNNN) or, if you know it, the exact record ID.">
              <input id="import-value" name="value" autoComplete="off" maxLength={100}
                aria-describedby="import-value-help" required />
            </FormField>
          </div>
          <div className="page-actions">
            <button className="btn btn--primary" type="submit" disabled={state.status === 'searching'}>
              <Search size={18} aria-hidden="true" />
              {state.status === 'searching' ? 'Searching…' : 'Search'}
            </button>
          </div>
        </form>
        <Alert tone="error" role="alert">{error}</Alert>
        {outcome && (
          <Alert tone={outcome.tone} role="status">
            {outcome.message}
            {outcome.patientId && (
              <> <Link to={`/patients/${encodeURIComponent(outcome.patientId)}`}>Open profile</Link></>
            )}
          </Alert>
        )}
        <ImportResultsView state={state} page={page} importing={importing} onImport={importOne} onPage={setPage} />
      </Card>
      <p className="muted">
        To create a new patient record instead, use <Link to="/patients/new">Register patient</Link>.
      </p>
    </section>
  )
}

export default ImportPatientPage
