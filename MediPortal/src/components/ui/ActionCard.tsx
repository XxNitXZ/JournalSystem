import type { ReactNode } from 'react'
import { ChevronRight, type LucideIcon } from 'lucide-react'
import { Link } from 'react-router'

interface ActionCardProps {
  to: string
  title: string
  description: string
  icon: LucideIcon
  /** Optional indicator next to the title, e.g. a count of items awaiting action. */
  badge?: ReactNode
}

/** A large, clearly labelled link to an existing feature. */
function ActionCard({ to, title, description, icon: Icon, badge }: ActionCardProps) {
  return (
    <Link to={to} className="action-card">
      <span className="action-card__icon" aria-hidden="true"><Icon size={24} /></span>
      <span className="action-card__text">
        <span className="action-card__title">{title}{badge}</span>
        <span className="action-card__description">{description}</span>
      </span>
      <ChevronRight className="action-card__arrow" size={20} aria-hidden="true" />
    </Link>
  )
}

export default ActionCard
