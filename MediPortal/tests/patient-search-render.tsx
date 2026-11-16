import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import PatientSearchResults from '../src/features/patientmanagement/PatientSearchResults.tsx'
import type { PatientDto } from '../src/features/patientmanagement/types.ts'

export function renderResults(patients: PatientDto[]) {
  return renderToStaticMarkup(<MemoryRouter><PatientSearchResults patients={patients} /></MemoryRouter>)
}
