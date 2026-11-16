import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { JournalAccessStatusMessage } from '../src/features/journalaccess/JournalAccessStatusCard.tsx'
import { JournalAccessRequestList } from '../src/features/journalaccess/JournalAccessRequestsPage.tsx'
import { JournalAccessReview } from '../src/features/journalaccess/JournalAccessReviewPage.tsx'
import type {
  JournalAccessRequestDetail,
  JournalAccessRequestSummary,
  PatientJournalStatus,
} from '../src/features/journalaccess/types.ts'
import PatientProfileView from '../src/features/patientmanagement/PatientProfileView.tsx'
import type { PatientProfileDto } from '../src/features/patientmanagement/types.ts'
import CountBadge from '../src/components/ui/CountBadge.tsx'

function inRouter(element: React.ReactNode) {
  return renderToStaticMarkup(<MemoryRouter>{element}</MemoryRouter>)
}

export function renderStatus(access: PatientJournalStatus, requesting = false) {
  return inRouter(<JournalAccessStatusMessage access={access} requesting={requesting} onRequest={() => {}} />)
}

export function renderList(requests: JournalAccessRequestSummary[]) {
  return inRouter(<JournalAccessRequestList requests={requests} />)
}

export function renderReview(detail: JournalAccessRequestDetail, pending = false) {
  return inRouter(<JournalAccessReview detail={detail} pending={pending} onDecide={() => {}} />)
}

export function renderProfile(patient: PatientProfileDto, label?: string) {
  return renderToStaticMarkup(<PatientProfileView patient={patient} label={label} />)
}

export function renderCount(count: number | null) {
  return renderToStaticMarkup(<CountBadge count={count} label={(n) => `${n} awaiting review`} />)
}
