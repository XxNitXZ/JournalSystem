import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { CreateEncounterFormView, type PractitionerState, type OrganizationState } from '../src/features/medicalrecord/CreateEncounterForm.tsx'
import EncountersSection, {
  EncounterListView,
  type EncounterListState,
} from '../src/features/medicalrecord/EncountersSection.tsx'
import type { PractitionerOption } from '../src/features/medicalrecord/types.ts'

export function renderList(state: EncounterListState, emptyText = 'No encounters have been recorded yet.') {
  return renderToStaticMarkup(<EncounterListView state={state} emptyText={emptyText} />)
}

export function renderSection(state: EncounterListState, createFor?: string, creating = false,
  practitioners?: PractitionerOption[]) {
  return renderToStaticMarkup(
    <MemoryRouter>
      <EncountersSection load={async () => ({ encounters: [], complete: true, excludedCount: 0 })} createFor={createFor} initialState={state} initiallyCreating={creating}
        initialPractitioners={practitioners} />
    </MemoryRouter>,
  )
}

export function renderForm(practitionerState: PractitionerState, pending = false, error = '', organizationState?: OrganizationState) {
  return renderToStaticMarkup(
    <CreateEncounterFormView practitionerState={practitionerState} organizationState={organizationState} defaultVisitTime="2026-11-10T14:30" pending={pending}
      error={error} onSubmit={() => {}} onCancel={() => {}} />,
  )
}
