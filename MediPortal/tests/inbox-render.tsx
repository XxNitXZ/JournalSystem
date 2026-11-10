import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { InboxList } from '../src/features/messaging/ClinicianInbox.tsx'
import { MessageHistoryView } from '../src/features/messaging/MessageHistoryView.tsx'
import type { MessageInboxConversation, MessageHistory } from '../src/features/messaging/types.ts'
export function renderInbox(items: MessageInboxConversation[], basePath: string, selected?: number) {
  return renderToStaticMarkup(<MemoryRouter><InboxList items={items} basePath={basePath} selected={selected} /></MemoryRouter>)
}
export function renderProfessionalHistory(messages: MessageHistory[], professionalId: number) {
  return renderToStaticMarkup(<MessageHistoryView messages={messages} currentUserId={professionalId} otherName="Synthetic Patient" incomingLabel="Patient message" />)
}
