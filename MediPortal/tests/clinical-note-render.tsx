import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import ClinicalNotesSection, {
  ClinicalNoteListView,
  type NoteListState,
} from '../src/features/medicalrecord/ClinicalNotesSection.tsx'
import {
  CreateClinicalNoteFormView,
  type NoteEncounterState,
} from '../src/features/medicalrecord/CreateClinicalNoteForm.tsx'
import type { EncounterSummary } from '../src/features/medicalrecord/types.ts'

export function renderNoteList(state: NoteListState, emptyText = 'No clinical notes have been written yet.') {
  return renderToStaticMarkup(<ClinicalNoteListView state={state} emptyText={emptyText} />)
}

export function renderNoteSection(state: NoteListState, createFor?: string, creating = false,
  encounters?: EncounterSummary[]) {
  return renderToStaticMarkup(
    <MemoryRouter>
      <ClinicalNotesSection load={async () => []} createFor={createFor} initialState={state}
        initiallyCreating={creating} initialEncounters={encounters} />
    </MemoryRouter>,
  )
}

export function renderNoteForm(encounterState: NoteEncounterState, encounterId = '', content = '', pending = false,
  error = '') {
  return renderToStaticMarkup(
    <CreateClinicalNoteFormView encounterState={encounterState} encounterId={encounterId} content={content}
      pending={pending} error={error} onEncounterChange={() => {}} onContentChange={() => {}} onSubmit={() => {}}
      onCancel={() => {}} />,
  )
}
