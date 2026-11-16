import { renderToStaticMarkup } from 'react-dom/server'
import PatientProfileView from '../src/features/patientmanagement/PatientProfileView.tsx'
import type { PatientDto } from '../src/features/patientmanagement/types.ts'

export function renderProfile(patient: PatientDto) {
  return renderToStaticMarkup(<PatientProfileView patient={patient} />)
}
