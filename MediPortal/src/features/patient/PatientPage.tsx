import { ArrowRight, HeartPulse, UserRound } from 'lucide-react'
import { Link } from 'react-router'
import Alert from '../../components/ui/Alert.tsx'
import Card from '../../components/ui/Card.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { accountFullName } from '../auth/accountName.ts'
import { useAuth } from '../auth/AuthContext.ts'
import { personalGreeting } from '../auth/greeting.ts'
import { roleLabels } from '../auth/roleLabels.ts'
import JournalAccessStatusCard from '../journalaccess/JournalAccessStatusCard.tsx'

/** Dashboard for PATIENT accounts: medical record access and the account itself. */
function PatientPage() {
  const { user } = useAuth()

  return (
    <section className="page" aria-labelledby="patient-title">
      <div className="welcome">
        <PageHeader titleId="patient-title" eyebrow={personalGreeting(user)} title="Patient dashboard" icon={HeartPulse}
          description="Your personal space in MediCore." />
        {user && <p className="welcome__account">Signed in as <span className="welcome__email">{user.email}</span></p>}
      </div>

      <div className="card-grid">
        <JournalAccessStatusCard />

        <Card title="Your account" titleId="patient-account" icon={UserRound}>
          <dl className="details">
            <dt>Name</dt>
            <dd>{(user && accountFullName(user)) ?? 'Not on file'}</dd>
            <dt>Email</dt>
            <dd>{user?.email}</dd>
            <dt>Account type</dt>
            <dd>{user ? roleLabels[user.role] : ''}</dd>
          </dl>
          <p className="card__note">
            <Link to="/login" className="text-link">View your account<ArrowRight size={16} aria-hidden="true" /></Link>
          </p>
        </Card>
      </div>

      <Alert tone="info">
        Secure messaging will be available in a future release.
      </Alert>
    </section>
  )
}

export default PatientPage
