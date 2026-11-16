import { useEffect, useRef, useState } from 'react'
import { CircleCheck, CircleX, Clock, FileHeart, FileSearch, Hourglass, Send, type LucideIcon } from 'lucide-react'
import { Link } from 'react-router'
import Alert from '../../components/ui/Alert.tsx'
import Card from '../../components/ui/Card.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import { getMyJournalAccessStatus, requestJournalAccess } from './journalAccessService.ts'
import { patientStatusText, rejectedExplanation, requestAccessErrorMessage } from './journalAccessText.ts'
import type { PatientJournalStatus } from './types.ts'

type StatusState = { status: 'loading' } | { status: 'failed' } | { status: 'loaded'; access: PatientJournalStatus }

const icons: Record<PatientJournalStatus, LucideIcon> = {
  NONE: FileSearch,
  AWAITING_RECORD: Hourglass,
  PENDING: Clock,
  APPROVED: CircleCheck,
  REJECTED: CircleX,
}

const tones: Record<PatientJournalStatus, string> = {
  NONE: 'none',
  AWAITING_RECORD: 'pending',
  PENDING: 'pending',
  APPROVED: 'approved',
  REJECTED: 'rejected',
}

interface StatusMessageProps {
  access: PatientJournalStatus
  /** True while the request for access is being sent. */
  requesting?: boolean
  onRequest?: () => void
}

/** Presentation of one status with its action; exported for rendering tests. */
export function JournalAccessStatusMessage({ access, requesting = false, onRequest }: StatusMessageProps) {
  const Icon = icons[access]
  return (
    <div className="journal-status">
      <p className={`journal-status__message journal-status__message--${tones[access]}`} role="status">
        <Icon size={20} aria-hidden="true" />
        <span>{patientStatusText[access]}</span>
      </p>
      {access === 'REJECTED' && <p className="muted">{rejectedExplanation}</p>}
      {access === 'NONE' && (
        <div className="journal-status__actions">
          <button type="button" className="btn btn--primary" disabled={requesting} onClick={onRequest}>
            <Send size={18} aria-hidden="true" />
            {requesting ? 'Sending request…' : 'Request Journal Access'}
          </button>
        </div>
      )}
      {access === 'APPROVED' && (
        <div className="journal-status__actions">
          <Link to="/patient/record" className="btn btn--primary">
            <FileHeart size={18} aria-hidden="true" />
            My Medical Record
          </Link>
        </div>
      )}
    </div>
  )
}

const requestedText: Partial<Record<PatientJournalStatus, string>> = {
  AWAITING_RECORD: 'Your request has been sent.',
  PENDING: 'Your request has been sent to a doctor for approval.',
}

/**
 * The PATIENT dashboard's journal access card. Loading it never creates a request: a request is only sent when
 * the patient clicks "Request Journal Access". Access is only ever granted by a doctor.
 */
function JournalAccessStatusCard() {
  const [state, setState] = useState<StatusState>({ status: 'loading' })
  const [requesting, setRequesting] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const sending = useRef(false)

  useEffect(() => {
    const controller = new AbortController()
    getMyJournalAccessStatus({ signal: controller.signal })
      .then((access) => { if (!controller.signal.aborted) setState({ status: 'loaded', access }) })
      .catch(() => { if (!controller.signal.aborted) setState({ status: 'failed' }) })
    return () => controller.abort()
  }, [])

  async function request() {
    if (sending.current) return
    sending.current = true
    setRequesting(true)
    setError('')
    setSuccess('')
    try {
      const access = await requestJournalAccess()
      setState({ status: 'loaded', access })
      setSuccess(requestedText[access] ?? '')
    } catch (failure: unknown) {
      setError(requestAccessErrorMessage(failure))
      // Show the current state, e.g. if the request was already decided.
      getMyJournalAccessStatus().then((access) => setState({ status: 'loaded', access })).catch(() => {})
    } finally {
      sending.current = false
      setRequesting(false)
    }
  }

  return (
    <Card title="Medical record" titleId="patient-journal-access" icon={FileHeart}>
      {state.status === 'loading' && <LoadingState label="Checking your medical record access…" />}
      {state.status === 'failed' && (
        <Alert tone="error" role="alert">Your medical record access could not be checked. Please try again later.</Alert>
      )}
      {state.status === 'loaded' && (
        <JournalAccessStatusMessage access={state.access} requesting={requesting} onRequest={request} />
      )}
      <Alert tone="error" role="alert">{error}</Alert>
      <Alert tone="success" role="status">{success}</Alert>
    </Card>
  )
}

export default JournalAccessStatusCard
