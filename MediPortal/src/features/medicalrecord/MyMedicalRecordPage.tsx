import { useEffect, useState } from 'react'
import { ArrowLeft, FileHeart } from 'lucide-react'
import { Link } from 'react-router'
import Alert from '../../components/ui/Alert.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { ownRecordErrorMessage } from '../journalaccess/journalAccessText.ts'
import PatientProfileView from '../patientmanagement/PatientProfileView.tsx'
import { getOwnPatientRecord } from '../patientmanagement/patientService.ts'
import type { PatientProfileDto } from '../patientmanagement/types.ts'

type RecordState = { status: 'loading' } | { status: 'failed'; message: string } | { status: 'loaded'; record: PatientProfileDto }

/**
 * The signed-in PATIENT's own medical record (PATIENT only). MediCore decides which record this is from the
 * approved link of the account; nothing in the URL selects it.
 */
function MyMedicalRecordPage() {
  const [state, setState] = useState<RecordState>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    getOwnPatientRecord({ signal: controller.signal })
      .then((record) => { if (!controller.signal.aborted) setState({ status: 'loaded', record }) })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'failed', message: ownRecordErrorMessage(failure) })
      })
    return () => controller.abort()
  }, [])

  return (
    <section className="page" aria-labelledby="my-record-title">
      <PageHeader titleId="my-record-title" eyebrow="Patient" title="My Medical Record" icon={FileHeart}
        description="Your personal details as recorded by your care provider." />
      {state.status === 'loading' && <div className="card"><LoadingState label="Loading your medical record…" /></div>}
      {state.status === 'failed' && <Alert tone="error" role="alert">{state.message}</Alert>}
      {state.status === 'loaded' && <PatientProfileView patient={state.record} label="Your medical record" />}
      <p><Link to="/patient" className="text-link"><ArrowLeft size={18} aria-hidden="true" />Back to dashboard</Link></p>
    </section>
  )
}

export default MyMedicalRecordPage
