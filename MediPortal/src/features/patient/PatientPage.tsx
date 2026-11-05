import { useAuth } from '../auth/AuthContext.ts'

function PatientPage() {
  const { user } = useAuth()

  return (
    <section className="page" aria-labelledby="patient-title">
      <h1 id="patient-title">Patient home</h1>
      <p className="page__lead">Welcome, {user?.email}.</p>
      <p>Patient information and messaging will be implemented later.</p>
    </section>
  )
}

export default PatientPage
