import type { ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'

interface CardProps {
  title?: string
  titleId?: string
  icon?: LucideIcon
  className?: string
  children: ReactNode
}

/** Rounded surface with an optional icon and heading; rendered as a labelled section when it has a title. */
function Card({ title, titleId, icon: Icon, className, children }: CardProps) {
  const classes = className ? `card ${className}` : 'card'
  if (!title) return <div className={classes}>{children}</div>
  return (
    <section className={classes} aria-labelledby={titleId}>
      <div className="card__header">
        {Icon && <span className="card__icon" aria-hidden="true"><Icon size={20} /></span>}
        <h2 id={titleId}>{title}</h2>
      </div>
      {children}
    </section>
  )
}

export default Card
