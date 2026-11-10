import { useEffect, useRef, useState, type SubmitEvent } from 'react'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import { sendReply, sendMessage } from './messageService.ts'
import { submitReply } from './replyForm.ts'

export default function ReplyForm({ targetId, refresh, mode = 'clinician' }: { targetId: number; refresh: (signal: AbortSignal) => Promise<void>; mode?: 'patient' | 'clinician' }) {
  const [content, setContent] = useState('')
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const guard = useRef(false)
  const lifecycle = useRef<AbortController | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    lifecycle.current = controller
    return () => controller.abort()
  }, [targetId])

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    const controller = lifecycle.current
    if (!controller || controller.signal.aborted || guard.current) return
    setError('')
    setSuccess('')
    setPending(true)
    const result = await submitReply(content, guard, controller.signal, {
      send: (text) => mode === 'patient'
        ? sendMessage({ recipientId: targetId, content: text }, { signal: controller.signal })
        : sendReply(targetId, text, { signal: controller.signal }),
      confirmed: () => { setContent(''); setSuccess('Your reply has been sent.') },
      refresh: () => refresh(controller.signal),
    })
    if (controller.signal.aborted) return
    setPending(false)
    if (result.status === 'error' || result.status === 'refreshError') setError(result.message)
  }

  return <form className="form" onSubmit={submit} aria-busy={pending} noValidate>
    <fieldset disabled={pending}>
      <legend>{mode === 'patient' ? 'Reply to this professional' : 'Reply to this patient'}</legend>
      <FormField htmlFor="reply-content" label="Reply" hintId="reply-length"
        hint={`${[...content].length.toLocaleString('en-US')} / 5,000 characters`}>
        <textarea id="reply-content" name="content" value={content} rows={6} required aria-describedby="reply-length"
          onChange={(event) => setContent(event.target.value)} />
      </FormField>
      <button className="btn btn--primary" type="submit">{pending ? 'Sending…' : 'Send reply'}</button>
    </fieldset>
    <Alert tone="info" role="status">{pending ? 'Sending reply and refreshing conversation…' : ''}</Alert>
    <Alert tone="error" role="alert">{error}</Alert>
    <Alert tone="success" role="status">{success}</Alert>
  </form>
}
