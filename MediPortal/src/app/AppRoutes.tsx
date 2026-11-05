import { Route, Routes } from 'react-router'
import AppLayout from '../components/layout/AppLayout.tsx'
import HomePage from '../pages/HomePage.tsx'
import NotFoundPage from '../pages/NotFoundPage.tsx'
import RegistrationForm from '../features/auth/RegistrationForm.tsx'
import LoginForm from '../features/auth/LoginForm.tsx'
import PatientPage from '../features/patient/PatientPage.tsx'
import DoctorPage from '../features/doctor/DoctorPage.tsx'
import StaffPage from '../features/staff/StaffPage.tsx'

function AppRoutes() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<HomePage />} />
        <Route path="register" element={<RegistrationForm />} />
        <Route path="login" element={<LoginForm />} />
        <Route path="patient" element={<PatientPage />} />
        <Route path="doctor" element={<DoctorPage />} />
        <Route path="staff" element={<StaffPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}

export default AppRoutes
