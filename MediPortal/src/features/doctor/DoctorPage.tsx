import { useAuth } from '../auth/AuthContext.ts'

function DoctorPage() {
  const { user } = useAuth()

  return (
    <section className="page" aria-labelledby="doctor-title">
      <h1 id="doctor-title">Doctor home</h1>
      <p className="page__lead">Welcome, {user?.email}.</p>
      <p>Clinical functionality will be implemented later.</p>
    </section>
  )
}

export default DoctorPage
