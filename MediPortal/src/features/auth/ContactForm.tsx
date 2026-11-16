import { useRef, useState, type SubmitEvent } from 'react'
import { Check, X } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import { useAuth } from './AuthContext.ts'
import { PHONE_NUMBER_HINT } from './phoneNumber.ts'
import { saveContact } from './profileForm.ts'
import { updateAccountContact, type AccountProfile } from './profileService.ts'
import type { UserDto } from './types.ts'

interface ContactFormProps {
  user: UserDto
  phoneNumber: string | null
  onCancel: () => void
  onSaved: (profile: AccountProfile) => void
}

/** Edits the signed-in user's e-mail (with the current password) and optional phone number. */
function ContactForm({ user, phoneNumber, onCancel, onSaved }: ContactFormProps) {
  const { acceptSession } = useAuth()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const submitting = useRef(false)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const fields = new FormData(event.currentTarget)
    submitting.current = true
    setPending(true)
    setError('')
    const result = await saveContact({
      email: String(fields.get('email') ?? ''),
      phoneNumber: String(fields.get('phoneNumber') ?? ''),
      currentPassword: String(fields.get('currentPassword') ?? ''),
    }, user, { update: (update) => updateAccountContact(update), accept: acceptSession })
    submitting.current = false
    setPending(false)
    if ('profile' in result) onSaved(result.profile)
    else setError(result.error)
  }

  return (
    <form className="form" onSubmit={submit} aria-busy={pending} noValidate>
      <fieldset disabled={pending}>
        <legend className="visually-hidden">Edit your contact information</legend>
        <FormField htmlFor="contact-email" label="Email">
          <input id="contact-email" name="email" type="email" autoComplete="email" maxLength={255}
            defaultValue={user.email} required autoFocus />
        </FormField>
        <FormField htmlFor="contact-phone" label="Phone number" hintId="contact-phone-help" hint={PHONE_NUMBER_HINT}>
          <input id="contact-phone" name="phoneNumber" type="tel" autoComplete="tel" maxLength={32}
            defaultValue={phoneNumber ?? ''} aria-describedby="contact-phone-help" />
        </FormField>
        <FormField htmlFor="contact-current-password" label="Current password" hintId="contact-password-help"
          hint="Required only when you change your email address.">
          <input id="contact-current-password" name="currentPassword" type="password" autoComplete="current-password"
            aria-describedby="contact-password-help" />
        </FormField>
        <div className="page-actions">
          <button className="btn btn--primary" type="submit">
            <Check size={18} aria-hidden="true" />{pending ? 'Saving…' : 'Save'}
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

export default ContactForm
