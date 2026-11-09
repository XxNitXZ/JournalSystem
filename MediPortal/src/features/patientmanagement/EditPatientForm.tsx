import { useRef, useState, type SubmitEvent } from 'react'
import { Save, X } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import { displayBirthDate, displayPersonnummer, NOT_RECORDED } from './patientProfileFormat.ts'
import {
  editableFields,
  patientUpdateErrorMessage,
  toUpdateRequest,
  validatePatientUpdate,
  type PatientUpdateFields,
} from './patientUpdate.ts'
import { updatePatient } from './patientService.ts'
import { genderLabels, genders, type PatientProfileDto } from './types.ts'

interface EditPatientFormViewProps {
  patient: PatientProfileDto
  /** True while the change is being saved. */
  pending: boolean
  error: string
  onSubmit: (event: SubmitEvent<HTMLFormElement>) => void
  onCancel: () => void
}

/** The name MediCore registered (official, otherwise the first one), split into first and last name. */
function registeredName(patient: PatientProfileDto): { first: string; last: string } {
  const name = patient.names.find((candidate) => candidate.use === 'official') ?? patient.names[0]
  return {
    first: name?.given.filter(Boolean).join(' ') || NOT_RECORDED,
    last: name?.family?.trim() || NOT_RECORDED,
  }
}

/**
 * The Edit Patient form, prefilled from the profile. Only the gender is editable; first name, last name,
 * personnummer and date of birth are shown read-only and are not form fields at all. Exported for rendering tests.
 */
export function EditPatientFormView({ patient, pending, error, onSubmit, onCancel }: EditPatientFormViewProps) {
  const initial = editableFields(patient)
  const name = registeredName(patient)
  return (
    <div className="card">
      <form className="form" onSubmit={onSubmit} aria-busy={pending} aria-labelledby="edit-patient-title" noValidate>
        <h2 id="edit-patient-title">Edit Patient</h2>
        <fieldset disabled={pending}>
          <legend className="visually-hidden">Patient details</legend>
          <dl className="details details--readonly" aria-label="Registered identity details">
            <dt>First name</dt>
            <dd>{name.first}</dd>
            <dt>Last name</dt>
            <dd>{name.last}</dd>
            <dt>Personnummer</dt>
            <dd>{displayPersonnummer(patient)}</dd>
            <dt>Date of birth</dt>
            <dd>{displayBirthDate(patient)}</dd>
          </dl>
          <p className="muted">Name, personnummer and date of birth cannot be changed after registration.</p>
          <FormField htmlFor="edit-patient-gender" label="Gender">
            <select id="edit-patient-gender" name="gender" defaultValue={initial.gender} required>
              <option value="" disabled>Select a gender</option>
              {genders.map((gender) => <option key={gender} value={gender}>{genderLabels[gender]}</option>)}
            </select>
          </FormField>
          <div className="page-actions">
            <button className="btn btn--primary" type="submit">
              <Save size={18} aria-hidden="true" />
              {pending ? 'Saving changes…' : 'Save Changes'}
            </button>
            <button className="btn btn--secondary" type="button" onClick={onCancel}>
              <X size={18} aria-hidden="true" />
              Cancel
            </button>
          </div>
        </fieldset>
      </form>
      <Alert tone="error" role="alert">{error}</Alert>
    </div>
  )
}

interface EditPatientFormProps {
  /** The patient as shown; must carry the version that was read. */
  patient: PatientProfileDto
  /** Called with the patient as stored in FHIR after a successful update. */
  onSaved: (updated: PatientProfileDto) => void
  /** Discards the unsaved change (the form is removed and starts from the profile again next time). */
  onCancel: () => void
}

/** Edits a patient's gender in HAPI FHIR through MediCore (DOCTOR and STAFF). */
function EditPatientForm({ patient, onSaved, onCancel }: EditPatientFormProps) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const saving = useRef(false)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (saving.current) return
    const data = new FormData(event.currentTarget)
    const fields: PatientUpdateFields = { gender: String(data.get('gender') ?? '') }
    const validationError = validatePatientUpdate(fields)
    setError(validationError ?? '')
    if (validationError) return
    if (!patient.version) {
      setError('The patient information could not be saved. Open the profile again and retry.')
      return
    }

    saving.current = true
    setPending(true)
    try {
      const updated = await updatePatient(patient.id, toUpdateRequest(fields), patient.version)
      onSaved(updated)
    } catch (failure: unknown) {
      setError(patientUpdateErrorMessage(failure))
    } finally {
      saving.current = false
      setPending(false)
    }
  }

  return <EditPatientFormView patient={patient} pending={pending} error={error} onSubmit={submit} onCancel={onCancel} />
}

export default EditPatientForm
