import { useEffect, useState } from 'react'
import { ArrowLeft, ChevronRight, ClipboardCheck, Hourglass, Inbox } from 'lucide-react'
import { Link } from 'react-router'
import Alert from '../../components/ui/Alert.tsx'
import Badge from '../../components/ui/Badge.tsx'
import Card from '../../components/ui/Card.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { listJournalAccessRequests } from './journalAccessService.ts'
import {
  formatDateTime,
  formatPersonnummer,
  journalAccessErrorMessage,
  personName,
  requestStatusLabels,
  statusTones,
} from './journalAccessText.ts'
import type { JournalAccessRequestSummary } from './types.ts'

type ListState = { status: 'loading' } | { status: 'failed'; message: string } | { status: 'loaded'; requests: JournalAccessRequestSummary[] }

/** One list of requests; exported for rendering tests. Shows account data only, never record identifiers. */
export function JournalAccessRequestList({ requests }: { requests: JournalAccessRequestSummary[] }) {
  return (
    <ul className="request-list">
      {requests.map((request) => {
        const name = personName(request.givenName, request.familyName)
        return (
          <li key={request.id} className="request-list__item">
            <span className="request-list__name">{name}</span>
            <span className="request-list__personnummer">{formatPersonnummer(request.personnummer)}</span>
            <span className="request-list__meta">
              {request.email}
              <br />
              {request.status === 'PENDING' || request.status === 'AWAITING_RECORD'
                ? `Requested ${formatDateTime(request.createdAt)}`
                : `Reviewed ${formatDateTime(request.reviewedAt)}`}
            </span>
            <Badge tone={statusTones[request.status]}>{requestStatusLabels[request.status]}</Badge>
            <Link to={`/doctor/journal-requests/${request.id}`} className="btn btn--secondary btn--sm"
              aria-label={`Review the request from ${name}`}>
              Review<ChevronRight size={16} aria-hidden="true" />
            </Link>
          </li>
        )
      })}
    </ul>
  )
}

/**
 * Journal access requests for DOCTOR accounts: requests ready for review first, then requests still waiting for
 * a registered patient record (not actionable), then reviewed ones.
 */
function JournalAccessRequestsPage() {
  const [state, setState] = useState<ListState>({ status: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    listJournalAccessRequests({ signal: controller.signal })
      .then((requests) => { if (!controller.signal.aborted) setState({ status: 'loaded', requests }) })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'failed', message: journalAccessErrorMessage(failure) })
      })
    return () => controller.abort()
  }, [])

  const pending = state.status === 'loaded' ? state.requests.filter((request) => request.status === 'PENDING') : []
  const awaiting = state.status === 'loaded'
    ? state.requests.filter((request) => request.status === 'AWAITING_RECORD') : []
  const reviewed = state.status === 'loaded'
    ? state.requests.filter((request) => request.status === 'APPROVED' || request.status === 'REJECTED') : []

  return (
    <section className="page" aria-labelledby="journal-requests-title">
      <PageHeader titleId="journal-requests-title" eyebrow="Patient management" title="Journal access requests"
        icon={ClipboardCheck}
        description="Patients who have asked for access to their medical record. Review each request before giving access." />

      {state.status === 'loading' && <div className="card"><LoadingState label="Loading journal access requests…" /></div>}
      {state.status === 'failed' && <Alert tone="error" role="alert">{state.message}</Alert>}
      {state.status === 'loaded' && (
        <>
          <Card title={`Awaiting review (${pending.length})`} titleId="journal-requests-pending" icon={Inbox}>
            {pending.length === 0
              ? <p className="muted">There are no requests awaiting review.</p>
              : <JournalAccessRequestList requests={pending} />}
          </Card>
          {awaiting.length > 0 && (
            <Card title={`Awaiting medical record (${awaiting.length})`} titleId="journal-requests-awaiting" icon={Hourglass}>
              <p className="muted">
                These patients have asked for access, but no patient record with their personnummer has been registered
                yet. The request becomes ready for review when the record is registered.
              </p>
              <JournalAccessRequestList requests={awaiting} />
            </Card>
          )}
          {reviewed.length > 0 && (
            <Card title="Reviewed" titleId="journal-requests-reviewed" icon={ClipboardCheck}>
              <JournalAccessRequestList requests={reviewed} />
            </Card>
          )}
        </>
      )}
      <p><Link to="/doctor" className="text-link"><ArrowLeft size={18} aria-hidden="true" />Back to dashboard</Link></p>
    </section>
  )
}

export default JournalAccessRequestsPage
