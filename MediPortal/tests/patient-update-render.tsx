import { renderToStaticMarkup } from 'react-dom/server'
import type { Role } from '../src/features/auth/types.ts'
import { EditPatientFormView } from '../src/features/patientmanagement/EditPatientForm.tsx'
import { ProfileWithActions } from '../src/features/patientmanagement/PatientProfilePage.tsx'
import type { PatientProfileDto } from '../src/features/patientmanagement/types.ts'

export function renderProfileWithActions(patient: PatientProfileDto, role: Role | undefined) {
  return renderToStaticMarkup(<ProfileWithActions patient={patient} role={role} onEdit={() => {}} />)
}

export function renderEditForm(patient: PatientProfileDto, pending = false, error = '') {
  return renderToStaticMarkup(
    <EditPatientFormView patient={patient} pending={pending} error={error} onSubmit={() => {}} onCancel={() => {}} />,
  )
}
