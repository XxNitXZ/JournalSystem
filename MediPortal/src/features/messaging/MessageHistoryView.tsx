import Alert from '../../components/ui/Alert.tsx'
import { messageTime } from './conversationText.ts'
import type { MessageHistory } from './types.ts'

export function MessageHistoryView({ messages, currentUserId, otherName, incomingLabel = 'Reply' }: {
  messages: MessageHistory[]; currentUserId: number; otherName: string; incomingLabel?: string
}) {
  if (!messages.length) return <Alert tone="info" role="status">There are no messages in this conversation.</Alert>
  // Preserve backend ordering, including PostgreSQL microsecond precision.
  return <ol className="message-history" aria-label="Message history">
    {messages.map((message) => {
      const own = message.senderId === currentUserId
      return <li key={message.id} className={`message-entry ${own ? 'message-entry--own' : ''}`}>
        <p className="message-entry__heading"><strong>{own ? 'You' : otherName}</strong>
          <span>{own ? 'Sent' : incomingLabel} · <time dateTime={message.createdAt}>{messageTime(message.createdAt)}</time></span>
        </p>
        <p className="message-entry__content">{message.content}</p>
      </li>
    })}
  </ol>
}

