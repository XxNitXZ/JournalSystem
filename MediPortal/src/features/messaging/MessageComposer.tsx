import { useEffect, useRef, useState, type SubmitEvent } from 'react'
import { Link } from 'react-router'
import { Send } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import Card from '../../components/ui/Card.tsx'
import FormField from '../../components/ui/FormField.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { getMessageRecipients, sendMessage } from './messageService.ts'
import { recipientLoadError, submitMessage } from './messageForm.ts'
import type { MessageRecipient } from './types.ts'

type Directory = { status: 'loading' } | { status: 'failed'; error: string }
  | { status: 'loaded'; recipients: MessageRecipient[] }

export function MessageComposerForm({ recipients }: { recipients: MessageRecipient[] }) {
  const [recipient, setRecipient] = useState('')
  const [content, setContent] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const submitting = useRef(false)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    setError('')
    setSuccess('')
    setPending(true)
    const result = await submitMessage(recipient, content, recipients, submitting, sendMessage)
    setPending(false)
    if (result.status === 'sent') {
      setContent('')
      setSuccess('Your message has been sent.')
    } else if (result.status === 'error') setError(result.message)
  }

  if (recipients.length === 0) return <Alert tone="info" role="status">No message recipients are available.</Alert>
  return (
    <form className="form" onSubmit={submit} aria-busy={pending} noValidate>
      <fieldset disabled={pending}>
        <legend className="visually-hidden">New message</legend>
        <FormField htmlFor="message-recipient" label="Recipient">
          <select id="message-recipient" name="recipientId" required value={recipient}
            onChange={(event) => setRecipient(event.target.value)}>
            <option value="">Choose a recipient</option>
            {recipients.map((item) => <option key={item.id} value={item.id}>
              {item.displayName} — {item.role === 'DOCTOR' ? 'Doctor' : 'Staff'}
            </option>)}
          </select>
        </FormField>
        <FormField htmlFor="message-content" label="Message" hintId="message-length"
          hint={`${[...content].length.toLocaleString('en-US')} / 5,000 characters`}>
          <textarea id="message-content" name="content" rows={8} required value={content}
            aria-describedby="message-length" onChange={(event) => setContent(event.target.value)} />
        </FormField>
        <button type="submit" className="btn btn--primary">
          <Send size={18} aria-hidden="true" />{pending ? 'Sending…' : 'Send message'}
        </button>
      </fieldset>
      <Alert tone="info" role="status">{pending ? 'Sending your message…' : ''}</Alert>
      <Alert tone="error" role="alert">{error}</Alert>
      <Alert tone="success" role="status">{success}</Alert>
    </form>
  )
}

function MessageComposer() {
  const [directory, setDirectory] = useState<Directory>({ status: 'loading' })
  useEffect(() => {
    const controller = new AbortController()
    getMessageRecipients({ signal: controller.signal })
      .then((recipients) => { if (!controller.signal.aborted) setDirectory({ status: 'loaded', recipients }) })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setDirectory({ status: 'failed', error: recipientLoadError(error) })
      })
    return () => controller.abort()
  }, [])

  return (
    <section className="page" aria-labelledby="message-title">
      <PageHeader titleId="message-title" title="Send a message" eyebrow="Messages" icon={Send}
        description="Contact a doctor or member of staff." />
      <Card title="New message" titleId="message-card">
        {directory.status === 'loading' && <LoadingState label="Loading recipients…" />}
        {directory.status === 'failed' && <Alert tone="error" role="alert">{directory.error}</Alert>}
        {directory.status === 'loaded' && <MessageComposerForm recipients={directory.recipients} />}
      </Card>
      <p><Link to="/patient" className="text-link">Back to dashboard</Link></p>
    </section>
  )
}

export default MessageComposer
