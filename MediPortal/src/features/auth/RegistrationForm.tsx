import { useRef, useState, type SubmitEvent } from 'react'
import { UserPlus } from 'lucide-react'
import { Link, Navigate } from 'react-router'
import AuthLayout from '../../components/layout/AuthLayout.tsx'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import { useAuth } from './AuthContext.ts'
import { registrationErrorMessage } from './registrationErrorMessage.ts'
import { registerAccount } from './registrationService.ts'
import { dashboardPath, roleLabels } from './roleLabels.ts'
import { roles, type Role } from './types.ts'
import { validateAccountNames, validateAccountPersonnummer, validateRegistration } from './validation.ts'
import { normalizePersonnummer } from '../patientmanagement/personnummer.ts'
import { INVALID_PHONE_NUMBER, normalizePhoneNumber, PHONE_NUMBER_HINT } from './phoneNumber.ts'
import { accountFirstName } from './accountName.ts'

function RegistrationForm() {
  const { user, loading, error: sessionError, acceptSession } = useAuth()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const submitting = useRef(false)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const form = event.currentTarget
    const fields = new FormData(form)
    const email = String(fields.get('email') ?? '')
    const password = String(fields.get('password') ?? '')
    const confirmation = String(fields.get('confirmation') ?? '')
    const role = String(fields.get('role') ?? '')
    const givenName = String(fields.get('givenName') ?? '')
    const familyName = String(fields.get('familyName') ?? '')
    const personnummer = String(fields.get('personnummer') ?? '')
    const phone = normalizePhoneNumber(String(fields.get('phoneNumber') ?? ''))
    setSuccess('')
    const validationError = validateAccountNames(givenName, familyName)
      ?? validateAccountPersonnummer(personnummer)
      ?? (phone.ok ? null : INVALID_PHONE_NUMBER)
      ?? validateRegistration(email, password, confirmation, role)
    setError(validationError ?? '')
    if (validationError) return

    submitting.current = true
    setPending(true)
    try {
      const { account, session } = await registerAccount({
        email, password, role: role as Role, givenName: givenName.trim(), familyName: familyName.trim(),
        personnummer: normalizePersonnummer(personnummer) ?? personnummer.trim(),
        phoneNumber: phone.ok ? phone.value : null,
      })
      form.reset()
      if (session) {
        // PATIENT accounts are signed in by MediCore; the session update redirects to the dashboard.
        acceptSession(session)
        return
      }
      const name = accountFirstName(account)
      setSuccess(`${name ? `Thank you, ${name}. ` : ''}Your ${roleLabels[account.role].toLowerCase()} account has been created. Sign in to continue.`)
    } catch (failure: unknown) {
      setError(registrationErrorMessage(failure))
    } finally {
      submitting.current = false
      setPending(false)
    }
  }

  if (loading) return <LoadingState label="Checking your session…" />
  if (sessionError) return <p role="alert" className="alert alert--error">{sessionError} <Link to="/login">Sign in</Link></p>
  if (user) return <Navigate to={dashboardPath(user.role)} replace />

  return (
    <AuthLayout titleId="register-title">
      <div>
        <h1 id="register-title">Create an account</h1>
        <p className="auth__lead">Create your MediCore account. All fields except the phone number are required.</p>
      </div>
      <form className="form" onSubmit={submit} aria-busy={pending} noValidate>
        <fieldset disabled={pending}>
          <legend className="visually-hidden">Registration details</legend>
          <div className="form__row">
            <FormField htmlFor="register-given-name" label="First name">
              <input id="register-given-name" name="givenName" autoComplete="given-name" maxLength={100} required />
            </FormField>
            <FormField htmlFor="register-family-name" label="Last name">
              <input id="register-family-name" name="familyName" autoComplete="family-name" maxLength={100} required />
            </FormField>
          </div>
          <FormField htmlFor="register-personnummer" label="Personnummer" hintId="register-personnummer-help"
            hint="12 digits, YYYYMMDDNNNN or YYYYMMDD-NNNN. Used to find your medical record. Use a fictional identity.">
            <input id="register-personnummer" name="personnummer" inputMode="numeric" autoComplete="off" maxLength={13}
              placeholder="YYYYMMDDNNNN" aria-describedby="register-personnummer-help" required />
          </FormField>
          <FormField htmlFor="register-email" label="Email">
            <input id="register-email" name="email" type="email" autoComplete="email" maxLength={255} required />
          </FormField>
          <FormField htmlFor="register-phone" label="Phone number (optional)" hintId="register-phone-help"
            hint={PHONE_NUMBER_HINT}>
            <input id="register-phone" name="phoneNumber" type="tel" autoComplete="tel" maxLength={32}
              aria-describedby="register-phone-help" />
          </FormField>
          <FormField htmlFor="register-password" label="Password" hintId="password-help"
            hint="Up to 72 characters. Some special characters count as more than one.">
            <input id="register-password" name="password" type="password" autoComplete="new-password"
              aria-describedby="password-help" required />
          </FormField>
          <FormField htmlFor="register-confirmation" label="Confirm password">
            <input id="register-confirmation" name="confirmation" type="password" autoComplete="new-password" required />
          </FormField>
          <FormField htmlFor="register-role" label="Account type" hintId="role-help"
            hint="Patients are signed in right away. Doctors and staff sign in after creating their account.">
            <select id="register-role" name="role" defaultValue="" aria-describedby="role-help" required>
              <option value="" disabled>Select an account type</option>
              {roles.map((role) => <option key={role} value={role}>{roleLabels[role]}</option>)}
            </select>
          </FormField>
          <button className="btn btn--primary btn--block" type="submit">
            <UserPlus size={18} aria-hidden="true" />
            {pending ? 'Creating account…' : 'Create account'}
          </button>
        </fieldset>
      </form>
      <Alert tone="error" role="alert">{error}</Alert>
      <Alert tone="success" role="status">
        {success && <>{success} <Link to="/login">Sign in</Link></>}
      </Alert>
      <p className="form__footer">Already have an account? <Link to="/login">Sign in</Link></p>
    </AuthLayout>
  )
}

export default RegistrationForm
