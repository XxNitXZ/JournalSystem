import type { ReactNode } from 'react'

type BadgeTone = 'neutral' | 'primary' | 'success' | 'warning' | 'danger'

/** Small label, e.g. for a role, an identifier or a status. */
function Badge({ tone = 'neutral', children }: { tone?: BadgeTone; children: ReactNode }) {
  return <span className={tone === 'neutral' ? 'badge' : `badge badge--${tone}`}>{children}</span>
}

export default Badge
