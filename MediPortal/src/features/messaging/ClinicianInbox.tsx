import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { Inbox } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import Card from '../../components/ui/Card.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { useAuth } from '../auth/AuthContext.ts'
import { MessageHistoryView } from './MessageHistoryView.tsx'
import { getProfessionalInbox, getInboxMessages } from './messageService.ts'
import { conversationError, messageTime } from './conversationText.ts'
import { loadInboxRequest } from './inboxRequest.ts'
import type { MessageInboxConversation, MessageHistory } from './types.ts'

type State<T> = { status: 'loading' } | { status: 'error'; error: string } | { status: 'loaded'; data: T }

export function InboxList({ items, basePath, selected }: { items: MessageInboxConversation[]; basePath: string; selected?: number }) {
  if (!items.length) return <Alert tone="info" role="status">Your inbox is empty. Patient messages will appear here.</Alert>
  return <ul className="conversation-list">{items.map((item) => <li key={item.patientId}>
    <Link to={`${basePath}/${item.patientId}`} className="conversation-link" aria-current={selected === item.patientId ? 'page' : undefined}>
      <strong>{item.displayName}</strong>
      <span>{item.messageCount} {item.messageCount === 1 ? 'message' : 'messages'}</span>
      <span>Latest: <time dateTime={item.lastMessageAt}>{messageTime(item.lastMessageAt)}</time></span>
    </Link>
  </li>)}</ul>
}

function InboxThread({ conversation, professionalId }: { conversation: MessageInboxConversation; professionalId: number }) {
  const [state, setState] = useState<State<MessageHistory[]>>({ status: 'loading' })
  useEffect(() => loadInboxRequest((signal) => getInboxMessages(conversation.patientId, { signal }),
    (data) => setState({ status: 'loaded', data }),
    (error) => setState({ status: 'error', error: conversationError(error) })), [conversation.patientId])
  return <Card title={conversation.displayName} titleId="inbox-thread-title">
    {state.status === 'loading' && <LoadingState label="Loading patient conversation…" />}
    {state.status === 'error' && <Alert tone="error" role="alert">{state.error}</Alert>}
    {state.status === 'loaded' && <MessageHistoryView messages={state.data} currentUserId={professionalId}
      otherName={conversation.displayName} incomingLabel="Patient message" />}
  </Card>
}

function ClinicianInbox() {
  const { user } = useAuth()
  const { patientId } = useParams()
  const selected = patientId && /^[1-9]\d*$/.test(patientId) && Number.isSafeInteger(Number(patientId)) ? Number(patientId) : undefined
  const basePath = user?.role === 'DOCTOR' ? '/doctor/inbox' : '/staff/inbox'
  const [state, setState] = useState<State<MessageInboxConversation[]>>({ status: 'loading' })
  useEffect(() => loadInboxRequest((signal) => getProfessionalInbox({ signal }),
    (data) => setState({ status: 'loaded', data }),
    (error) => setState({ status: 'error', error: conversationError(error) })), [])
  const conversation = state.status === 'loaded' ? state.data.find((item) => item.patientId === selected) : undefined
  return <section className="page" aria-labelledby="inbox-title">
    <PageHeader titleId="inbox-title" title="Patient inbox" eyebrow="Messages" icon={Inbox}
      description="Read conversations with patients who have contacted you." />
    {state.status === 'loading' && <LoadingState label="Loading patient inbox…" />}
    {state.status === 'error' && <Alert tone="error" role="alert">{state.error}</Alert>}
    {state.status === 'loaded' && <div className="conversation-layout">
      <Card title="Patient conversations" titleId="inbox-list-title"><InboxList items={state.data} basePath={basePath} selected={selected} /></Card>
      {patientId ? conversation && user ?
        <InboxThread key={`${user.id}:${conversation.patientId}`} conversation={conversation} professionalId={user.id} /> :
        <Alert tone="error" role="alert">This conversation is not available.</Alert> :
        state.data.length > 0 && <Alert tone="info" role="status">Choose a patient conversation to view its messages.</Alert>}
    </div>}
  </section>
}
export default ClinicianInbox
