import { useEffect, useState } from 'react'
import { ArrowRight, IdCard, KeyRound, Mail, Pencil, UserRound } from 'lucide-react'
import { Link } from 'react-router'
import Alert from '../../components/ui/Alert.tsx'
import Card from '../../components/ui/Card.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { accountFullName } from './accountName.ts'
import ChangePasswordForm from './ChangePasswordForm.tsx'
import ContactForm from './ContactForm.tsx'
import { getAccountProfile, type AccountProfile } from './profileService.ts'
import { dashboardPath, roleLabels } from './roleLabels.ts'
import type { UserDto } from './types.ts'

export type AccountPanel = 'contact' | 'password' | null

type ProfileState = { status: 'loading' } | { status: 'failed' } | { status: 'loaded'; profile: AccountProfile }

/** A 12-digit personnummer as YYYYMMDD-NNNN. */
function formatPersonnummer(value: string): string {
  return /^\d{12}$/.test(value) ? `${value.slice(0, 8)}-${value.slice(8)}` : value
}

interface AccountOverviewProps {
  user: UserDto
  /** Panel open at first (for tests). */
  initialPanel?: AccountPanel
  /** Account data already known (for tests); otherwise it is loaded from MediCore. */
  initialProfile?: AccountProfile
}

/**
 * Account page for a signed-in user: personal information (name and personnummer, read-only after registration),
 * contact information (e-mail and phone, editable) and a separate password change.
 */
function AccountOverview({ user, initialPanel = null, initialProfile }: AccountOverviewProps) {
  const [panel, setPanel] = useState<AccountPanel>(initialPanel)
  const [state, setState] = useState<ProfileState>(
    initialProfile ? { status: 'loaded', profile: initialProfile } : { status: 'loading' })
  const [saved, setSaved] = useState('')
  const fullName = accountFullName(user)

  useEffect(() => {
    if (initialProfile) return undefined
    const controller = new AbortController()
    getAccountProfile({ signal: controller.signal })
      .then((profile) => { if (!controller.signal.aborted) setState({ status: 'loaded', profile }) })
      .catch(() => { if (!controller.signal.aborted) setState({ status: 'failed' }) })
    return () => controller.abort()
  }, [initialProfile])

  const profile = state.status === 'loaded' ? state.profile : null
  const pendingText = state.status === 'loading' ? 'Loading…' : 'Not available'
  const personnummer = profile ? (profile.personnummer ? formatPersonnummer(profile.personnummer) : 'Not on file')
    : pendingText
  const phone = profile ? (profile.phoneNumber ?? 'Not provided') : pendingText

  function open(next: AccountPanel) {
    setSaved('')
    setPanel(next)
  }

  return (
    <section className="page" aria-labelledby="login-title">
      <PageHeader titleId="login-title" eyebrow="Account" title="Your account" icon={UserRound}
        description="Your personal information, contact details and password." />
      <div className="card-grid">
        <Card title="Personal information" titleId="account-personal" icon={IdCard}>
          <dl className="details">
            <dt>Name</dt>
            <dd>{fullName ?? 'Not on file'}</dd>
            {fullName && (
              <>
                <dt>First name</dt>
                <dd>{user.givenName?.trim()}</dd>
                <dt>Last name</dt>
                <dd>{user.familyName?.trim()}</dd>
              </>
            )}
            <dt>Personnummer</dt>
            <dd>{personnummer}</dd>
          </dl>
          <p className="card__note">Your name and personnummer cannot be changed after registration.</p>
        </Card>
        <Card title="Contact information" titleId="account-contact" icon={Mail}>
          {panel === 'contact' && profile ? (
            <ContactForm user={user} phoneNumber={profile.phoneNumber} onCancel={() => setPanel(null)}
              onSaved={(updated) => {
                setState({ status: 'loaded', profile: updated })
                setPanel(null)
                setSaved('Your contact information has been updated.')
              }} />
          ) : (
            <>
              <dl className="details">
                <dt>Email</dt>
                <dd>{user.email}</dd>
                <dt>Phone</dt>
                <dd>{phone}</dd>
              </dl>
              {profile && (
                <div className="card__actions">
                  <button type="button" className="btn btn--secondary btn--sm" onClick={() => open('contact')}>
                    <Pencil size={16} aria-hidden="true" />Edit contact information
                  </button>
                </div>
              )}
            </>
          )}
          {state.status === 'failed' && (
            <Alert tone="error" role="alert">Your account details could not be loaded. Please try again later.</Alert>
          )}
        </Card>
        <Card title="Password" titleId="account-password" icon={KeyRound}>
          {panel === 'password' ? (
            <ChangePasswordForm onCancel={() => setPanel(null)} onSaved={() => {
              setPanel(null)
              setSaved('Your password has been changed.')
            }} />
          ) : (
            <div className="card__actions">
              <button type="button" className="btn btn--secondary btn--sm" onClick={() => open('password')}>
                <KeyRound size={16} aria-hidden="true" />Change password
              </button>
            </div>
          )}
        </Card>
        <Card title="Account details" titleId="account-details" icon={UserRound}>
          <dl className="details">
            <dt>Account type</dt>
            <dd>{roleLabels[user.role]}</dd>
          </dl>
          <p className="card__note">Your account type cannot be changed here.</p>
        </Card>
      </div>
      <Alert tone="success" role="status">{saved}</Alert>
      <div className="page-actions">
        <Link to={dashboardPath(user.role)} className="btn btn--primary">
          Go to your dashboard<ArrowRight size={18} aria-hidden="true" />
        </Link>
      </div>
    </section>
  )
}

export default AccountOverview
