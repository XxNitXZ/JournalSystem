import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import DiagnosesSection, {
  DiagnosisListView,
  type DiagnosisListState,
} from '../src/features/medicalrecord/DiagnosesSection.tsx'
import {
  CreateDiagnosisFormView,
  type DiagnosisEncounterState,
  type FieldError,
} from '../src/features/medicalrecord/CreateDiagnosisForm.tsx'
import { emptyDiagnosisFields, type DiagnosisFields } from '../src/features/medicalrecord/diagnosisForm.ts'
import type { EncounterSummary } from '../src/features/medicalrecord/types.ts'

export function renderDiagnosisList(state: DiagnosisListState, emptyText = 'No diagnoses have been recorded yet.') {
  return renderToStaticMarkup(<DiagnosisListView state={state} emptyText={emptyText} />)
}

export function renderDiagnosisSection(state: DiagnosisListState, createFor?: string, creating = false,
  encounters?: EncounterSummary[]) {
  return renderToStaticMarkup(
    <MemoryRouter>
      <DiagnosesSection load={async () => []} createFor={createFor} initialState={state} initiallyCreating={creating}
        initialEncounters={encounters} />
    </MemoryRouter>,
  )
}

export function renderDiagnosisForm(encounterState: DiagnosisEncounterState, fields: Partial<DiagnosisFields> = {},
  fieldError: FieldError | null = null, pending = false, error = '') {
  return renderToStaticMarkup(
    <CreateDiagnosisFormView encounterState={encounterState} fields={{ ...emptyDiagnosisFields, ...fields }}
      fieldError={fieldError} pending={pending} error={error} today="2026-11-12" onChange={() => {}}
      onSubmit={() => {}} onCancel={() => {}} />,
  )
}
