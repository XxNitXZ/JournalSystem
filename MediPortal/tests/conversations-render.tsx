import { renderToStaticMarkup } from 'react-dom/server'
import { ConversationList } from '../src/features/messaging/PatientMessages.tsx'
import { MessageHistoryView } from '../src/features/messaging/MessageHistoryView.tsx'
import { MemoryRouter } from 'react-router'
import type { MessageConversation, MessageHistory } from '../src/features/messaging/types.ts'
export function renderList(items: MessageConversation[], selected?: number) {
  return renderToStaticMarkup(<MemoryRouter><ConversationList conversations={items} selected={selected} /></MemoryRouter>)
}
export function renderHistory(items: MessageHistory[], patientId: number) {
  return renderToStaticMarkup(<MessageHistoryView messages={items} currentUserId={patientId} otherName="Synthetic Doctor" />)
}
