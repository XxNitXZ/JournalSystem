import { Link } from 'react-router'
import { useAuth } from '../auth/AuthContext.ts'

function PatientPage() {
  const { user, loading, error } = useAuth()

  if (loading) return <p role="status">Checking your session…</p>
  if (error) return <p role="alert">{error} <Link to="/login">Sign in</Link></p>
  if (!user) return <p>Please <Link to="/login">sign in</Link> to access the patient page.</p>
  if (user.role !== 'PATIENT') return <p>This page is available to patient accounts only.</p>

  return (
    <section className="page" aria-labelledby="patient-title">
      <h1 id="patient-title">Patient home</h1>
      <p className="page__lead">Welcome, {user.email}.</p>
      <p>Patient information and messaging will be implemented later.</p>
    </section>
  )
}

export default PatientPage
