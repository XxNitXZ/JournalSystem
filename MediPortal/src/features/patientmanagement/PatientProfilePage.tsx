import { useCallback, useEffect, useState } from 'react'
import { ArrowLeft, ClipboardList, Pencil } from 'lucide-react'
import { Link, useParams } from 'react-router'
import Alert from '../../components/ui/Alert.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { useAuth } from '../auth/AuthContext.ts'
import { dashboardPath } from '../auth/roleLabels.ts'
import type { Role } from '../auth/types.ts'
import EditPatientForm from './EditPatientForm.tsx'
import PatientProfileView from './PatientProfileView.tsx'
import { isValidPatientId } from './patientId.ts'
import { patientProfileErrorMessage } from './patientProfileErrorMessage.ts'
import { getPatient } from './patientService.ts'
import { canEditPatients } from './patientUpdate.ts'
import type { PatientProfileDto } from './types.ts'
import ClinicalNotesSection from '../medicalrecord/ClinicalNotesSection.tsx'
import { listPatientNotes } from '../medicalrecord/clinicalNoteService.ts'
import DiagnosesSection from '../medicalrecord/DiagnosesSection.tsx'
import { listPatientDiagnoses } from '../medicalrecord/diagnosisService.ts'
import EncountersSection from '../medicalrecord/EncountersSection.tsx'
import { listPatientEncounters } from '../medicalrecord/encounterService.ts'
import type { ApiRequestOptions } from '../../services/apiClient.ts'

type ProfileState = { status: 'loading' } | { status: 'failed'; message: string } | { status: 'loaded'; patient: PatientProfileDto }

interface ProfileWithActionsProps {
  patient: PatientProfileDto
  role: Role | undefined
  onEdit: () => void
}

/** The profile with an "Edit Patient" action for doctors and staff only; exported for rendering tests. */
export function ProfileWithActions({ patient, role, onEdit }: ProfileWithActionsProps) {
  const actions = canEditPatients(role)
    ? (
      <button type="button" className="btn btn--secondary btn--sm" onClick={onEdit}>
        <Pencil size={16} aria-hidden="true" />
        Edit Patient
      </button>
    )
    : undefined
  return <PatientProfileView patient={patient} actions={actions} />
}

/** Loads one Patient by ID; remounted (key) for every ID so that no other Patient's data is ever shown. */
function PatientProfile({ id }: { id: string }) {
  const { user } = useAuth()
  const [state, setState] = useState<ProfileState>({ status: 'loading' })
  const [reload, setReload] = useState(0)
  const [editing, setEditing] = useState(false)
  const [success, setSuccess] = useState('')
  const loadEncounters = useCallback((options: ApiRequestOptions) => listPatientEncounters(id, options), [id])
  const loadNotes = useCallback((options: ApiRequestOptions) => listPatientNotes(id, options), [id])
  const loadDiagnoses = useCallback((options: ApiRequestOptions) => listPatientDiagnoses(id, options), [id])

  useEffect(() => {
    const controller = new AbortController()
    getPatient(id, { signal: controller.signal })
      .then((patient) => { if (!controller.signal.aborted) setState({ status: 'loaded', patient }) })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'failed', message: patientProfileErrorMessage(failure) })
      })
    return () => controller.abort()
  }, [id, reload])

  if (state.status === 'loading') return <div className="card"><LoadingState label="Loading patient profile…" /></div>
  if (state.status === 'failed') return <Alert tone="error" role="alert">{state.message}</Alert>

  function saved(updated: PatientProfileDto) {
    setEditing(false)
    setState({ status: 'loaded', patient: updated })
    setSuccess('The patient information has been updated.')
    // Read the profile again, so that exactly what is stored in the patient record is shown.
    setReload((value) => value + 1)
  }

  return (
    <>
      {editing && canEditPatients(user?.role)
        ? <EditPatientForm patient={state.patient} onSaved={saved} onCancel={() => setEditing(false)} />
        : (
          <ProfileWithActions patient={state.patient} role={user?.role}
            onEdit={() => { setSuccess(''); setEditing(true) }} />
        )}
      <Alert tone="success" role="status">{success}</Alert>
      <EncountersSection load={loadEncounters} createFor={canEditPatients(user?.role) ? id : undefined} />
      <DiagnosesSection load={loadDiagnoses} createFor={canEditPatients(user?.role) ? id : undefined} />
      <ClinicalNotesSection load={loadNotes} createFor={canEditPatients(user?.role) ? id : undefined} />
    </>
  )
}

/** Patient profile for DOCTOR and STAFF accounts (US-04.02), read by MediCore directly from HAPI FHIR. */
function PatientProfilePage() {
  const { id = '' } = useParams()
  const { user } = useAuth()
  const home = user ? dashboardPath(user.role) : '/'

  return (
    <section className="page" aria-labelledby="patient-profile-title">
      <PageHeader titleId="patient-profile-title" eyebrow="Patient management" title="Patient profile"
        icon={ClipboardList} description="Personal and demographic details for this patient." />
      {isValidPatientId(id)
        ? <PatientProfile key={id} id={id} />
        : <Alert tone="error" role="alert">The patient ID is not valid.</Alert>}
      <p><Link to={home} className="text-link"><ArrowLeft size={18} aria-hidden="true" />Back to dashboard</Link></p>
    </section>
  )
}

export default PatientProfilePage
