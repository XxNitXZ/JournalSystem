import { ClipboardCheck, Inbox, FolderSearch, Stethoscope, UserPlus, Users, type LucideIcon } from 'lucide-react'
import ActionCard from '../../components/ui/ActionCard.tsx'
import Alert from '../../components/ui/Alert.tsx'
import Card from '../../components/ui/Card.tsx'
import CountBadge from '../../components/ui/CountBadge.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { useAuth } from '../auth/AuthContext.ts'
import { personalGreeting } from '../auth/greeting.ts'
import PatientSearch from '../patientmanagement/PatientSearch.tsx'
import { usePendingJournalAccessCount } from '../journalaccess/usePendingJournalAccessCount.ts'

interface ClinicalDashboardProps {
  role: 'doctor' | 'staff'
}

const copy: Record<ClinicalDashboardProps['role'], { title: string; icon: LucideIcon; intro: string; register: string }> = {
  doctor: {
    title: 'Doctor dashboard',
    icon: Stethoscope,
    intro: 'Register new patients and review patient profiles before consultations.',
    register: 'Create a new patient record with a validated Swedish personnummer.',
  },
  staff: {
    title: 'Staff dashboard',
    icon: Users,
    intro: 'Register arriving patients and look up their profiles for the care team.',
    register: 'Register a patient at reception with a validated Swedish personnummer.',
  },
}

const pendingLabel = (count: number) => `${count} ${count === 1 ? 'request' : 'requests'} awaiting review`

/** Dashboard for DOCTOR and STAFF accounts; it links only to features that exist. */
function ClinicalDashboard({ role }: ClinicalDashboardProps) {
  const { user } = useAuth()
  const text = copy[role]
  const pendingRequests = usePendingJournalAccessCount(role === 'doctor')

  return (
    <section className="page" aria-labelledby={`${role}-title`}>
      <div className="welcome">
        <PageHeader titleId={`${role}-title`} eyebrow={personalGreeting(user)} title={text.title} icon={text.icon}
          description={text.intro} />
        {user && <p className="welcome__account">Signed in as <span className="welcome__email">{user.email}</span></p>}
      </div>

      <div className="card-grid">
        <ActionCard to="/patients/new" title="Register a patient" description={text.register} icon={UserPlus} />
        <ActionCard to={`/${role}/inbox`} title="Patient inbox" description="Read messages from your patients." icon={Inbox} />
        {role === 'doctor' && (
          <ActionCard to="/doctor/journal-requests" title="Journal access requests" icon={ClipboardCheck}
            description="Review patients asking for access to their medical record."
            badge={<CountBadge count={pendingRequests} label={pendingLabel} />} />
        )}
        <Card title="Find a patient" titleId={`${role}-find-patient`} icon={FolderSearch} className="card--wide">
          <PatientSearch />
        </Card>
      </div>

      <Alert tone="info">
        Further clinical functionality, such as medical records, will be available in a future release.
      </Alert>
    </section>
  )
}

export default ClinicalDashboard
