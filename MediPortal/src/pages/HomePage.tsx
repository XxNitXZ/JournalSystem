import { Activity, ArrowRight, IdCard, LogIn, ShieldCheck, Stethoscope, UserPlus, Users } from 'lucide-react'
import { Link } from 'react-router'
import HeroIllustration from '../components/brand/HeroIllustration.tsx'
import BackendStatus from '../components/ui/BackendStatus.tsx'
import Badge from '../components/ui/Badge.tsx'
import Card from '../components/ui/Card.tsx'
import { useAuth } from '../features/auth/AuthContext.ts'
import { dashboardPath } from '../features/auth/roleLabels.ts'

/** Features that are implemented today; nothing on this page promises more. */
const features = [
  {
    icon: ShieldCheck,
    title: 'Private and secure',
    text: 'Every patient, doctor and staff member signs in to a personal account and sees only what their work requires.',
  },
  {
    icon: IdCard,
    title: 'Patient registration',
    text: 'Doctors and staff register new patients with their Swedish personnummer. A patient can only be registered once.',
  },
  {
    icon: Stethoscope,
    title: 'Patient profiles',
    text: 'Doctors and staff open a patient’s profile to check personal and demographic details before a visit.',
  },
  {
    icon: Users,
    title: 'Made for care teams',
    text: 'Doctors and reception staff each have a workspace for their daily tasks, and patients have their own dashboard.',
  },
]

function HomePage() {
  const { user, loading, error } = useAuth()
  const anonymous = !loading && !error && !user

  return (
    <div>
      <section className="hero" aria-labelledby="home-title">
        <div>
          <p className="hero__eyebrow"><Badge tone="primary"><Activity size={14} aria-hidden="true" />Healthcare records platform</Badge></p>
          <h1 id="home-title">Patient records, <span>connected and secure.</span></h1>
          <p className="hero__lead">
            MediCore helps care teams register patients and keep their details at hand, while patients get a
            personal account of their own.
          </p>
          <div className="page-actions">
            {anonymous && (
              <>
                <Link to="/login" className="btn btn--primary"><LogIn size={18} aria-hidden="true" />Sign in</Link>
                <Link to="/register" className="btn btn--secondary"><UserPlus size={18} aria-hidden="true" />Create an account</Link>
              </>
            )}
            {!loading && !error && user && (
              <Link to={dashboardPath(user.role)} className="btn btn--primary">
                Go to your dashboard<ArrowRight size={18} aria-hidden="true" />
              </Link>
            )}
          </div>
        </div>
        <HeroIllustration />
      </section>

      <section className="section" aria-labelledby="features-title">
        <div className="section__header">
          <h2 id="features-title">What MediCore does today</h2>
          <p>Available now, with more features on the way.</p>
        </div>
        <div className="card-grid">
          {features.map(({ icon: Icon, title, text }) => (
            <div key={title} className="card feature">
              <span className="card__icon" aria-hidden="true"><Icon size={20} /></span>
              <h3>{title}</h3>
              <p>{text}</p>
            </div>
          ))}
        </div>
      </section>

      <section className="section" aria-labelledby="status-title">
        <Card title="Service status" titleId="status-title" icon={Activity}>
          <BackendStatus />
        </Card>
      </section>
    </div>
  )
}

export default HomePage
