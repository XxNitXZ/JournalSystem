import { useRef, useState, type SubmitEvent } from 'react'
import { registrationErrorMessage } from './registrationErrorMessage.ts'
import { registerUser } from './registrationService.ts'
import { roles, type Role } from './types.ts'
import { validateRegistration } from './validation.ts'

function RegistrationForm() {
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
    setSuccess('')
    const validationError = validateRegistration(email, password, confirmation, role)
    setError(validationError ?? '')
    if (validationError) return

    submitting.current = true
    setPending(true)
    try {
      const user = await registerUser({ email, password, role: role as Role })
      form.reset()
      setSuccess(`Account created for ${user.email} with role ${user.role}.`)
    } catch (failure: unknown) {
      setError(registrationErrorMessage(failure))
    } finally {
      submitting.current = false
      setPending(false)
    }
  }

  return (
    <section className="page" aria-labelledby="register-title">
      <h1 id="register-title">Create an account</h1>
      <p className="page__lead">Register with MediNexus. All fields are required.</p>
      <form className="registration-form" onSubmit={submit} aria-busy={pending}>
        <fieldset disabled={pending}>
          <legend>Registration details</legend>
          <label htmlFor="register-email">Email</label>
          <input id="register-email" name="email" type="email" autoComplete="email" maxLength={255} required />
          <label htmlFor="register-password">Password</label>
          <input id="register-password" name="password" type="password" autoComplete="new-password" aria-describedby="password-help" required />
          <p id="password-help">Use a nonblank password of at most 72 UTF-8 bytes. Some characters use more than one byte.</p>
          <label htmlFor="register-confirmation">Confirm password</label>
          <input id="register-confirmation" name="confirmation" type="password" autoComplete="new-password" required />
          <label htmlFor="register-role">Role</label>
          <select id="register-role" name="role" defaultValue="" required>
            <option value="" disabled>Select a role</option>
            {roles.map((role) => <option key={role} value={role}>{role}</option>)}
          </select>
          <button className="button-link" type="submit">{pending ? 'Creating account…' : 'Create account'}</button>
        </fieldset>
      </form>
      <p role="alert" className="registration-error">{error}</p>
      <p role="status">{success}</p>
    </section>
  )
}

export default RegistrationForm
