import { Link } from 'react-router'
import { useAuth } from '../auth/AuthContext.ts'

function DoctorPage() {
  const { user, loading, error } = useAuth()

  if (loading) return <p role="status">Checking your session…</p>
  if (error) return <p role="alert">{error} <Link to="/login">Sign in</Link></p>
  if (!user) return <p>Please <Link to="/login">sign in</Link> to access the doctor page.</p>
  if (user.role !== 'DOCTOR') return <p>This page is available to doctor accounts only.</p>

  return (
    <section className="page" aria-labelledby="doctor-title">
      <h1 id="doctor-title">Doctor home</h1>
      <p className="page__lead">Welcome, {user.email}.</p>
      <p>Clinical functionality will be implemented later.</p>
    </section>
  )
}

export default DoctorPage
