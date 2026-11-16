import { renderToStaticMarkup } from 'react-dom/server'
import ReplyForm from '../src/features/messaging/ReplyForm.tsx'
export function renderReply() {
  return renderToStaticMarkup(<ReplyForm targetId={42} refresh={async () => {}} />)
}

export function renderPatientReply() {
  return renderToStaticMarkup(<ReplyForm targetId={9} mode="patient" refresh={async () => {}} />)
}
