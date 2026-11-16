import { renderToStaticMarkup } from 'react-dom/server'
import { MessageComposerForm } from '../src/features/messaging/MessageComposer.tsx'
import type { MessageRecipient } from '../src/features/messaging/types.ts'
export function renderComposerForm(recipients: MessageRecipient[]) {
  return renderToStaticMarkup(<MessageComposerForm recipients={recipients} />)
}
