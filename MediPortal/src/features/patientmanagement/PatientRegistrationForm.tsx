import { useRef, useState, type SubmitEvent } from 'react'
import { UserPlus } from 'lucide-react'
import { Link } from 'react-router'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { patientErrorMessage } from './patientErrorMessage.ts'
import { isValidPatientId } from './patientId.ts'
import { registerPatient } from './patientService.ts'
import { normalizePersonnummer } from './personnummer.ts'
import { givenNames, validatePatientRegistration } from './patientValidation.ts'
import { genderLabels, genders, type Gender } from './types.ts'


/** Registers a new patient record in HAPI FHIR (US-04.01). Rendered for DOCTOR and STAFF accounts only. */
function PatientRegistrationForm() {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [registeredId, setRegisteredId] = useState('')
  const submitting = useRef(false)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const form = event.currentTarget
    const data = new FormData(form)
    const fields = {
      personnummer: String(data.get('personnummer') ?? ''),
      family: String(data.get('family') ?? ''),
      given: String(data.get('given') ?? ''),
      gender: String(data.get('gender') ?? ''),
    }
    setSuccess('')
    setRegisteredId('')
    const validationError = validatePatientRegistration(fields)
    setError(validationError ?? '')
    if (validationError) return

    submitting.current = true
    setPending(true)
    try {
      const patient = await registerPatient({
        // The date of birth is derived from the personnummer by MediCore.
        personnummer: normalizePersonnummer(fields.personnummer) ?? fields.personnummer.trim(),
        family: fields.family.trim(),
        given: givenNames(fields.given),
        gender: fields.gender as Gender,
      })
      form.reset()
      setSuccess('The patient has been registered.')
      if (isValidPatientId(patient.id)) setRegisteredId(patient.id)
    } catch (failure: unknown) {
      setError(patientErrorMessage(failure))
    } finally {
      submitting.current = false
      setPending(false)
    }
  }

  return (
    <section className="page page--narrow" aria-labelledby="patient-registration-title">
      <PageHeader titleId="patient-registration-title" eyebrow="Patient management" title="Register patient"
        icon={UserPlus} description="Add a new patient to MediCore. All fields are required." />
      <div className="card">
        <form className="form" onSubmit={submit} aria-busy={pending} autoComplete="off" noValidate>
          <fieldset disabled={pending}>
            <legend>Patient details</legend>
            <div className="form__section">
              <h2 className="form__section-title">Identity</h2>
              <FormField htmlFor="patient-personnummer" label="Personnummer" hintId="personnummer-help"
                hint="12 digits, YYYYMMDDNNNN or YYYYMMDD-NNNN, starting with the date of birth. The date of birth is taken from the personnummer. Use fictional identities only.">
                <input id="patient-personnummer" name="personnummer" inputMode="numeric" maxLength={13}
                  placeholder="YYYYMMDDNNNN" aria-describedby="personnummer-help" required />
              </FormField>
            </div>
            <div className="form__section">
              <h2 className="form__section-title">Name</h2>
              <div className="form__row">
                <FormField htmlFor="patient-given" label="Given names" hintId="given-help"
                  hint="Separate several given names with spaces.">
                  <input id="patient-given" name="given" aria-describedby="given-help" required />
                </FormField>
                <FormField htmlFor="patient-family" label="Family name">
                  <input id="patient-family" name="family" maxLength={100} required />
                </FormField>
              </div>
            </div>
            <div className="form__section">
              <h2 className="form__section-title">Demographics</h2>
              <FormField htmlFor="patient-gender" label="Gender">
                <select id="patient-gender" name="gender" defaultValue="" required>
                  <option value="" disabled>Select a gender</option>
                  {genders.map((gender) => <option key={gender} value={gender}>{genderLabels[gender]}</option>)}
                </select>
              </FormField>
            </div>
            <div className="page-actions">
              <button className="btn btn--primary" type="submit">
                <UserPlus size={18} aria-hidden="true" />
                {pending ? 'Registering patient…' : 'Register patient'}
              </button>
            </div>
          </fieldset>
        </form>
      </div>
      <Alert tone="error" role="alert">{error}</Alert>
      <Alert tone="success" role="status">
        {success && (
          <>
            {success}
            {registeredId && <> <Link to={`/patients/${encodeURIComponent(registeredId)}`}>View patient profile</Link></>}
          </>
        )}
      </Alert>
    </section>
  )
}

export default PatientRegistrationForm
