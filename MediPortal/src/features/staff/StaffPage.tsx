import { useAuth } from '../auth/AuthContext.ts'

function StaffPage() {
  const { user } = useAuth()

  return (
    <section className="page" aria-labelledby="staff-title">
      <h1 id="staff-title">Staff home</h1>
      <p className="page__lead">Welcome, {user?.email}.</p>
      <p>Clinical functionality will be implemented later.</p>
    </section>
  )
}

export default StaffPage
