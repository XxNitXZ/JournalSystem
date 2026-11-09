import { useEffect, useRef, useState, type SubmitEvent } from 'react'
import { Search } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import PatientSearchResults from './PatientSearchResults.tsx'
import { patientSearchErrorMessage } from './patientSearch.ts'
import { validateSearchQuery } from './searchQuery.ts'
import { searchPatients } from './patientService.ts'
import type { PatientDto } from './types.ts'

type SearchState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'failed'; message: string }
  | { status: 'done'; patients: PatientDto[] }

/** Finds patients by name or exact personnummer among the patients registered in MediCore. */
function PatientSearch() {
  const [state, setState] = useState<SearchState>({ status: 'idle' })
  const current = useRef<AbortController | null>(null)

  useEffect(() => () => current.current?.abort(), [])

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const query = String(new FormData(event.currentTarget).get('query') ?? '')
    const invalid = validateSearchQuery(query)
    if (invalid) {
      setState({ status: 'failed', message: invalid })
      return
    }
    // A new search replaces any search that is still running.
    current.current?.abort()
    const controller = new AbortController()
    current.current = controller
    setState({ status: 'loading' })
    try {
      const patients = await searchPatients(query.trim(), { signal: controller.signal })
      if (!controller.signal.aborted) setState({ status: 'done', patients })
    } catch (failure: unknown) {
      if (!controller.signal.aborted) setState({ status: 'failed', message: patientSearchErrorMessage(failure) })
    }
  }

  const count = state.status === 'done' ? state.patients.length : 0
  return (
    <div className="patient-search">
      <form className="form" onSubmit={submit} role="search" autoComplete="off" noValidate>
        <div className="form__inline">
          <FormField htmlFor="patient-search-query" label="Name or personnummer">
            <input id="patient-search-query" name="query" maxLength={100} aria-describedby="patient-search-help"
              placeholder="First and last name, or YYYYMMDD-NNNN" />
          </FormField>
          <button className="btn btn--primary" type="submit" disabled={state.status === 'loading'}>
            <Search size={18} aria-hidden="true" />{state.status === 'loading' ? 'Searching…' : 'Search'}
          </button>
        </div>
        <p id="patient-search-help" className="field__hint">
          Search by first name, last name or full name, or by a complete personnummer. Only patients registered in
          MediCore are shown.
        </p>
      </form>
      {state.status === 'loading' && <LoadingState label="Searching for patients…" />}
      <Alert tone="error" role="alert">{state.status === 'failed' ? state.message : ''}</Alert>
      <p role="status" className={state.status === 'done' ? 'search-results__summary' : 'visually-hidden'}>
        {state.status === 'done' && (count === 0 ? 'No patients match your search.'
          : `${count} ${count === 1 ? 'patient' : 'patients'} found.`)}
      </p>
      {state.status === 'done' && count > 0 && <PatientSearchResults patients={state.patients} />}
    </div>
  )
}

export default PatientSearch
