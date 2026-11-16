import type { ReactNode } from 'react'
import { CircleAlert, CircleCheck, Info } from 'lucide-react'

type Tone = 'error' | 'success' | 'info'

const icons = { error: CircleAlert, success: CircleCheck, info: Info }

interface AlertProps {
  tone: Tone
  /** "alert" for errors, "status" for confirmations and progress; omit for static notices. */
  role?: 'alert' | 'status'
  children?: ReactNode
}

/**
 * Feedback message. The element is always rendered so that it works as a live region from the start; without
 * content it is empty and hidden.
 */
function Alert({ tone, role, children }: AlertProps) {
  const hasContent = children !== undefined && children !== null && children !== false && children !== ''
  const Icon = icons[tone]
  return (
    <p role={role} className={`alert alert--${tone}`}>
      {hasContent && <Icon size={18} aria-hidden="true" />}
      {hasContent && <span>{children}</span>}
    </p>
  )
}

export default Alert
