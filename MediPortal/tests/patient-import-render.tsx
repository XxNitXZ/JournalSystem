import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { ImportResultsView, type ImportSearchState } from '../src/features/patientmanagement/ImportPatientPage.tsx'

export function renderResults(state: ImportSearchState, page = 1, importing: string | null = null) {
  return renderToStaticMarkup(
    <MemoryRouter>
      <ImportResultsView state={state} page={page} importing={importing} onImport={() => {}} onPage={() => {}} />
    </MemoryRouter>,
  )
}
