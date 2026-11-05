import { Link } from 'react-router'
import { useAuth } from '../auth/AuthContext.ts'

function StaffPage() {
  const { user, loading, error } = useAuth()

  if (loading) return <p role="status">Checking your session…</p>
  if (error) return <p role="alert">{error} <Link to="/login">Sign in</Link></p>
  if (!user) return <p>Please <Link to="/login">sign in</Link> to access the staff page.</p>
  if (user.role !== 'STAFF') return <p>This page is available to staff accounts only.</p>

  return (
    <section className="page" aria-labelledby="staff-title">
      <h1 id="staff-title">Staff home</h1>
      <p className="page__lead">Welcome, {user.email}.</p>
      <p>Clinical functionality will be implemented later.</p>
    </section>
  )
}

export default StaffPage
