import { useEffect, useRef, useState } from 'react'
import { ArrowLeft, Check, ClipboardCheck, FileHeart, RotateCcw, UserRound, X } from 'lucide-react'
import { Link, useLocation, useNavigate, useParams } from 'react-router'
import Alert from '../../components/ui/Alert.tsx'
import Badge from '../../components/ui/Badge.tsx'
import Card from '../../components/ui/Card.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import {
  announceJournalAccessChange,
  decideJournalAccessRequest,
  getJournalAccessRequest,
  type JournalAccessDecision,
} from './journalAccessService.ts'
import {
  decisionSuccess,
  formatDateTime,
  formatPersonnummer,
  journalAccessErrorMessage,
  personName,
  parseRequestId,
  requestStatusLabels,
  statusTones,
} from './journalAccessText.ts'
import type { JournalAccessRequestDetail } from './types.ts'

const genderNames: Record<string, string> = { female: 'Female', male: 'Male', other: 'Other', unknown: 'Unknown' }

interface ReviewProps {
  detail: JournalAccessRequestDetail
  pending: boolean
  onDecide: (decision: JournalAccessDecision) => void
}

/** The account next to the matched record, with the available decisions; exported for rendering tests. */
export function JournalAccessReview({ detail, pending, onDecide }: ReviewProps) {
  const { account, record } = detail
  const canApprove = detail.status === 'PENDING' && detail.recordStatus === 'AVAILABLE' && detail.personnummerMatches

  return (
    <>
      <div className="card">
        <div className="patient-summary__meta">
          <Badge tone={statusTones[detail.status]}>{requestStatusLabels[detail.status]}</Badge>
          <span className="muted">Requested {formatDateTime(detail.createdAt)}</span>
          {detail.reviewedAt && <span className="muted">Reviewed {formatDateTime(detail.reviewedAt)}</span>}
        </div>
      </div>

      <div className="comparison">
        <Card title="MediCore account" titleId="review-account" icon={UserRound}>
          <dl className="details">
            <dt>Name</dt>
            <dd>{personName(account.givenName, account.familyName)}</dd>
            <dt>Personnummer</dt>
            <dd>{formatPersonnummer(account.personnummer)}</dd>
            <dt>Email</dt>
            <dd>{account.email}</dd>
          </dl>
        </Card>
        <Card title="Patient record" titleId="review-record" icon={FileHeart}>
          {record ? (
            <dl className="details">
              <dt>Name</dt>
              <dd>{[...record.givenNames, record.familyName].filter(Boolean).join(' ') || 'Not recorded'}</dd>
              <dt>Personnummer</dt>
              <dd>{record.personnummer ? formatPersonnummer(record.personnummer) : 'Not recorded'}</dd>
              <dt>Date of birth</dt>
              <dd>{record.birthDate || 'Not recorded'}</dd>
              <dt>Gender</dt>
              <dd>{record.gender ? genderNames[record.gender] ?? record.gender : 'Not recorded'}</dd>
            </dl>
          ) : (
            <p className="muted">
              {detail.recordStatus === 'NOT_REGISTERED'
                ? 'No patient record with this personnummer has been registered yet. The request can be reviewed once it is registered.'
                : detail.recordStatus === 'NOT_FOUND'
                  ? 'The patient record no longer exists.'
                  : 'The patient record could not be loaded right now. Please try again later.'}
            </p>
          )}
        </Card>
      </div>

      {record && (detail.personnummerMatches
        ? <Alert tone="success">The personnummer of the account and the patient record match.</Alert>
        : <Alert tone="error">The personnummer of the account and the patient record do not match.</Alert>)}

      {detail.status === 'PENDING' && (
        <div className="review-actions">
          <button type="button" className="btn btn--primary" disabled={pending || !canApprove}
            onClick={() => onDecide('approve')}>
            <Check size={18} aria-hidden="true" />Approve access
          </button>
          <button type="button" className="btn btn--danger" disabled={pending} onClick={() => onDecide('reject')}>
            <X size={18} aria-hidden="true" />Reject
          </button>
        </div>
      )}
      {detail.status === 'REJECTED' && (
        <div className="review-actions">
          <button type="button" className="btn btn--secondary" disabled={pending} onClick={() => onDecide('reopen')}>
            <RotateCcw size={18} aria-hidden="true" />Reconsider request
          </button>
        </div>
      )}
    </>
  )
}

type DetailState = { status: 'loading' } | { status: 'failed'; message: string } | { status: 'loaded'; detail: JournalAccessRequestDetail }

/** Loads one request; remounted (key) for every ID so that no other request's data is ever shown. */
function RequestReview({ id }: { id: number }) {
  const navigate = useNavigate()
  const location = useLocation()
  const [state, setState] = useState<DetailState>({ status: 'loading' })
  const [reload, setReload] = useState(0)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  // After a reconsideration the new request is shown with the confirmation of the previous page.
  const [success, setSuccess] = useState(() =>
    (location.state as { reopened?: boolean } | null)?.reopened ? decisionSuccess.reopen : '')
  const deciding = useRef(false)

  useEffect(() => {
    const controller = new AbortController()
    getJournalAccessRequest(id, { signal: controller.signal })
      .then((detail) => { if (!controller.signal.aborted) setState({ status: 'loaded', detail }) })
      .catch((failure: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'failed', message: journalAccessErrorMessage(failure) })
      })
    return () => controller.abort()
  }, [id, reload])

  async function decide(decision: JournalAccessDecision) {
    if (deciding.current) return
    deciding.current = true
    setPending(true)
    setError('')
    setSuccess('')
    try {
      const result = await decideJournalAccessRequest(id, decision)
      announceJournalAccessChange()
      if (decision === 'reopen' && result.id !== id) {
        navigate(`/doctor/journal-requests/${result.id}`, { state: { reopened: true } })
        return
      }
      setSuccess(decisionSuccess[decision])
      setReload((value) => value + 1)
    } catch (failure: unknown) {
      setError(journalAccessErrorMessage(failure))
      // The request may have changed (e.g. decided by another doctor): show its current state.
      setReload((value) => value + 1)
    } finally {
      deciding.current = false
      setPending(false)
    }
  }

  return (
    <>
      {state.status === 'loading' && <div className="card"><LoadingState label="Loading the request…" /></div>}
      {state.status === 'failed' && <Alert tone="error" role="alert">{state.message}</Alert>}
      {state.status === 'loaded' && <JournalAccessReview detail={state.detail} pending={pending} onDecide={decide} />}
      <Alert tone="error" role="alert">{error}</Alert>
      <Alert tone="success" role="status">{success}</Alert>
    </>
  )
}

/** Review of one journal access request (DOCTOR only). */
function JournalAccessReviewPage() {
  const { id } = useParams()
  const requestId = parseRequestId(id)

  return (
    <section className="page" aria-labelledby="journal-review-title">
      <PageHeader titleId="journal-review-title" eyebrow="Journal access requests" title="Review request"
        icon={ClipboardCheck}
        description="Compare the account with the patient record. Approve only if they belong to the same person." />
      {requestId === null
        ? <Alert tone="error" role="alert">This journal access request was not found.</Alert>
        : <RequestReview key={requestId} id={requestId} />}
      <p>
        <Link to="/doctor/journal-requests" className="text-link">
          <ArrowLeft size={18} aria-hidden="true" />Back to journal access requests
        </Link>
      </p>
    </section>
  )
}

export default JournalAccessReviewPage
