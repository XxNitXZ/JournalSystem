import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import { MessagesSquare } from 'lucide-react'
import Alert from '../../components/ui/Alert.tsx'
import Card from '../../components/ui/Card.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import PageHeader from '../../components/ui/PageHeader.tsx'
import { useAuth } from '../auth/AuthContext.ts'
import { getConversations, getConversationMessages } from './messageService.ts'
import { conversationError, messageTime } from './conversationText.ts'
import type { MessageConversation, MessageHistory } from './types.ts'

type LoadState<T> = { status: 'loading' } | { status: 'error'; error: string } | { status: 'loaded'; data: T }

export function ConversationList({ conversations, selected }: { conversations: MessageConversation[]; selected?: number }) {
  if (!conversations.length) return <Alert tone="info" role="status">You have no conversations yet. Send a message to get started.</Alert>
  return <ul className="conversation-list">
    {conversations.map((item) => <li key={item.recipientId}>
      <Link to={`/patient/messages/${item.recipientId}`} aria-current={item.recipientId === selected ? 'page' : undefined}
        className="conversation-link">
        <strong>{item.displayName}</strong> · {item.role === 'DOCTOR' ? 'Doctor' : 'Staff'}
        <span>{item.messageCount} {item.messageCount === 1 ? 'message' : 'messages'}</span>
        <span>Latest: <time dateTime={item.lastMessageAt}>{messageTime(item.lastMessageAt)}</time></span>
      </Link>
    </li>)}
  </ul>
}

export function MessageHistoryView({ messages, patientId, professionalName }: {
  messages: MessageHistory[]; patientId: number; professionalName: string
}) {
  if (!messages.length) return <Alert tone="info" role="status">There are no messages in this conversation.</Alert>
  // Preserve backend ordering, including PostgreSQL microsecond precision.
  return <ol className="message-history" aria-label="Message history">
    {messages.map((message) => {
      const own = message.senderId === patientId
      return <li key={message.id} className={`message-entry ${own ? 'message-entry--own' : ''}`}>
        <p className="message-entry__heading"><strong>{own ? 'You' : professionalName}</strong>
          <span>{own ? 'Sent' : 'Reply'} · <time dateTime={message.createdAt}>{messageTime(message.createdAt)}</time></span>
        </p>
        <p className="message-entry__content">{message.content}</p>
      </li>
    })}
  </ol>
}

function ConversationThread({ conversation, patientId }: { conversation: MessageConversation; patientId: number }) {
  const [state, setState] = useState<LoadState<MessageHistory[]>>({ status: 'loading' })
  useEffect(() => {
    const controller = new AbortController()
    getConversationMessages(conversation.recipientId, { signal: controller.signal })
      .then((data) => { if (!controller.signal.aborted) setState({ status: 'loaded', data }) })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'error', error: conversationError(error) })
      })
    return () => controller.abort()
  }, [conversation.recipientId])
  return <Card title={`${conversation.displayName} · ${conversation.role === 'DOCTOR' ? 'Doctor' : 'Staff'}`} titleId="thread-title">
    {state.status === 'loading' && <LoadingState label="Loading conversation…" />}
    {state.status === 'error' && <Alert tone="error" role="alert">{state.error}</Alert>}
    {state.status === 'loaded' && <MessageHistoryView messages={state.data} patientId={patientId} professionalName={conversation.displayName} />}
  </Card>
}

function PatientMessages() {
  const { user } = useAuth()
  const { recipientId } = useParams()
  const selected = recipientId && /^[1-9]\d*$/.test(recipientId) && Number.isSafeInteger(Number(recipientId)) ? Number(recipientId) : undefined
  const [state, setState] = useState<LoadState<MessageConversation[]>>({ status: 'loading' })
  useEffect(() => {
    const controller = new AbortController()
    getConversations({ signal: controller.signal })
      .then((data) => { if (!controller.signal.aborted) setState({ status: 'loaded', data }) })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setState({ status: 'error', error: conversationError(error) })
      })
    return () => controller.abort()
  }, [])
  const conversation = state.status === 'loaded' ? state.data.find((item) => item.recipientId === selected) : undefined
  return <section className="page" aria-labelledby="messages-title">
    <PageHeader titleId="messages-title" title="Your conversations" eyebrow="Messages" icon={MessagesSquare}
      description="View messages you have exchanged with doctors and staff." />
    <p><Link className="btn btn--primary" to="/patient/messages/new">Send a new message</Link></p>
    {state.status === 'loading' && <LoadingState label="Loading conversations…" />}
    {state.status === 'error' && <Alert tone="error" role="alert">{state.error}</Alert>}
    {state.status === 'loaded' && <div className="conversation-layout">
      <Card title="Conversations" titleId="conversation-list-title"><ConversationList conversations={state.data} selected={selected} /></Card>
      {recipientId ? conversation && user ?
        <ConversationThread key={`${user.id}:${conversation.recipientId}`} conversation={conversation} patientId={user.id} /> :
        <Alert tone="error" role="alert">This conversation is not available.</Alert> :
        state.data.length > 0 && <Alert tone="info" role="status">Choose a conversation to view its messages.</Alert>}
    </div>}
  </section>
}

export default PatientMessages
