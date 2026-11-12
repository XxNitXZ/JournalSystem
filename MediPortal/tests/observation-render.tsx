import { renderToStaticMarkup } from 'react-dom/server'
import ObservationsSection, { ObservationListView, type ObservationListState } from '../src/features/medicalrecord/ObservationsSection.tsx'
export function renderObservations(state: ObservationListState, createFor?: string, creating = false) {
  return renderToStaticMarkup(<ObservationsSection load={async () => ({ observations: [], complete: true, excludedCount: 0 })} initialState={state} createFor={createFor} initiallyCreating={creating} />)
}
export function renderObservationList(state: ObservationListState) {
  return renderToStaticMarkup(<ObservationListView state={state} />)
}
