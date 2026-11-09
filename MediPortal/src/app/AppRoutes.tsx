import { Route, Routes } from 'react-router'
import AppLayout from '../components/layout/AppLayout.tsx'
import HomePage from '../pages/HomePage.tsx'
import NotFoundPage from '../pages/NotFoundPage.tsx'
import RegistrationForm from '../features/auth/RegistrationForm.tsx'
import LoginForm from '../features/auth/LoginForm.tsx'
import PatientPage from '../features/patient/PatientPage.tsx'
import DoctorPage from '../features/doctor/DoctorPage.tsx'
import StaffPage from '../features/staff/StaffPage.tsx'
import ProtectedRoute from '../features/auth/ProtectedRoute.tsx'
import PatientProfilePage from '../features/patientmanagement/PatientProfilePage.tsx'
import PatientRegistrationForm from '../features/patientmanagement/PatientRegistrationForm.tsx'
import JournalAccessRequestsPage from '../features/journalaccess/JournalAccessRequestsPage.tsx'
import JournalAccessReviewPage from '../features/journalaccess/JournalAccessReviewPage.tsx'
import MyMedicalRecordPage from '../features/medicalrecord/MyMedicalRecordPage.tsx'

function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="register" element={<RegistrationForm />} />
        <Route path="login" element={<LoginForm />} />
        <Route element={<ProtectedRoute role="PATIENT" />}>
          <Route path="patient" element={<PatientPage />} />
          <Route path="patient/record" element={<MyMedicalRecordPage />} />
        </Route>
        <Route element={<ProtectedRoute role="DOCTOR" />}>
          <Route path="doctor" element={<DoctorPage />} />
          <Route path="doctor/journal-requests" element={<JournalAccessRequestsPage />} />
          <Route path="doctor/journal-requests/:id" element={<JournalAccessReviewPage />} />
        </Route>
        <Route element={<ProtectedRoute role="STAFF" />}>
          <Route path="staff" element={<StaffPage />} />
        </Route>
        <Route element={<ProtectedRoute roles={['DOCTOR', 'STAFF']} />}>
          <Route path="patients/new" element={<PatientRegistrationForm />} />
          <Route path="patients/:id" element={<PatientProfilePage />} />
        </Route>
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default AppRoutes
