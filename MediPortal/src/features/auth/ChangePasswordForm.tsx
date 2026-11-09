import { useRef, useState, type SubmitEvent } from 'react'
import { Check, X } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import { validatePasswordChange } from './profileForm.ts'
import { accountErrorMessage, changePassword } from './profileService.ts'

interface ChangePasswordFormProps {
  onCancel: () => void
  onSaved: () => void
}

/** Changes the signed-in user's password; the current password is required and the session continues. */
function ChangePasswordForm({ onCancel, onSaved }: ChangePasswordFormProps) {
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const submitting = useRef(false)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const form = event.currentTarget
    const fields = new FormData(form)
    const change = {
      currentPassword: String(fields.get('currentPassword') ?? ''),
      newPassword: String(fields.get('newPassword') ?? ''),
      confirmPassword: String(fields.get('confirmPassword') ?? ''),
    }
    const invalid = validatePasswordChange(change)
    setError(invalid ?? '')
    if (invalid) return
    submitting.current = true
    setPending(true)
    try {
      await changePassword(change)
      form.reset()
      onSaved()
    } catch (failure: unknown) {
      setError(accountErrorMessage(failure))
    } finally {
      submitting.current = false
      setPending(false)
    }
  }

  return (
    <form className="form" onSubmit={submit} aria-busy={pending} noValidate>
      <fieldset disabled={pending}>
        <legend className="visually-hidden">Change your password</legend>
        <FormField htmlFor="password-current" label="Current password">
          <input id="password-current" name="currentPassword" type="password" autoComplete="current-password"
            required autoFocus />
        </FormField>
        <FormField htmlFor="password-new" label="New password" hintId="password-new-help"
          hint="Up to 72 characters. Some special characters count as more than one.">
          <input id="password-new" name="newPassword" type="password" autoComplete="new-password"
            aria-describedby="password-new-help" required />
        </FormField>
        <FormField htmlFor="password-confirm" label="Confirm new password">
          <input id="password-confirm" name="confirmPassword" type="password" autoComplete="new-password" required />
        </FormField>
        <div className="page-actions">
          <button className="btn btn--primary" type="submit">
            <Check size={18} aria-hidden="true" />{pending ? 'Changing password…' : 'Change password'}
          </button>
          <button className="btn btn--secondary" type="button" onClick={onCancel}>
            <X size={18} aria-hidden="true" />Cancel
          </button>
        </div>
      </fieldset>
      <Alert tone="error" role="alert">{error}</Alert>
    </form>
  )
}

export default ChangePasswordForm
