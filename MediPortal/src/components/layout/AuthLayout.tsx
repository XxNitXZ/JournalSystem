import type { ReactNode } from 'react'
import { HeartPulse, LockKeyhole, Stethoscope } from 'lucide-react'
import { LogoMark } from '../brand/Logo.tsx'

/** Two-column card for signing in and creating an account: welcome panel and form panel. */
function AuthLayout({ titleId, children }: { titleId: string; children: ReactNode }) {
  return (
    <section className="auth" aria-labelledby={titleId}>
      <div className="auth__brand">
        <LogoMark className="logo__mark" />
        <div>
          <h2>Welcome to MediCore</h2>
          <p>Patient care and health information, brought together in one place.</p>
        </div>
        <ul className="auth__points">
          <li><LockKeyhole size={18} aria-hidden="true" />Your account is protected and private to you.</li>
          <li><Stethoscope size={18} aria-hidden="true" />Doctors and staff register patients and look up their details.</li>
          <li><HeartPulse size={18} aria-hidden="true" />Patients have their own personal dashboard.</li>
        </ul>
      </div>
      <div className="auth__panel">{children}</div>
    </section>
  )
}

export default AuthLayout
