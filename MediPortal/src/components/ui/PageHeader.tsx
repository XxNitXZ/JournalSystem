import type { ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'

interface PageHeaderProps {
  /** ID of the h1, referenced by the page's aria-labelledby. */
  titleId: string
  title: string
  eyebrow?: string
  description?: ReactNode
  icon?: LucideIcon
}

/** Page title block. The h1 carries only its ID so that its text stays a plain, stable heading. */
function PageHeader({ titleId, title, eyebrow, description, icon: Icon }: PageHeaderProps) {
  return (
    <header className="page-header">
      {Icon && <span className="page-header__icon" aria-hidden="true"><Icon size={26} /></span>}
      <div>
        {eyebrow && <p className="page-header__eyebrow">{eyebrow}</p>}
        <h1 id={titleId}>{title}</h1>
        {description && <p className="page-header__description">{description}</p>}
      </div>
    </header>
  )
}

export default PageHeader
