import { ClipboardCheck, Send, LayoutDashboard, UserPlus, UserRound, type LucideIcon, MessagesSquare } from 'lucide-react'
import { NavLink } from 'react-router'
import CountBadge from '../ui/CountBadge.tsx'
import { useAuth } from '../../features/auth/AuthContext.ts'
import { dashboardPath } from '../../features/auth/roleLabels.ts'
import type { Role } from '../../features/auth/types.ts'
import { usePendingJournalAccessCount } from '../../features/journalaccess/usePendingJournalAccessCount.ts'

interface NavItem {
  to: string
  label: string
  icon: LucideIcon
  end?: boolean
  /** Shows the number of journal access requests awaiting review. */
  pendingCount?: boolean
}

/** Only links to features that exist for the role. */
function itemsFor(role: Role): NavItem[] {
  const dashboard: NavItem = { to: dashboardPath(role), label: 'Dashboard', icon: LayoutDashboard, end: true }
  const account: NavItem = { to: '/login', label: 'My account', icon: UserRound }
  if (role === 'PATIENT') return [
  dashboard,
  { to: '/patient/messages', label: 'Conversations', icon: MessagesSquare, end: true },
  { to: '/patient/messages/new', label: 'Send a message', icon: Send }, account
  ]
  const register: NavItem = { to: '/patients/new', label: 'Register patient', icon: UserPlus }
  const inbox: NavItem = { to: `/${role.toLowerCase()}/inbox`, label: 'Patient inbox', icon: MessagesSquare }
  if (role === 'STAFF') return [dashboard, register, inbox, account]
  const requests: NavItem = { to: '/doctor/journal-requests', label: 'Journal requests', icon: ClipboardCheck, pendingCount: true }
  return [dashboard, register, inbox, requests, account]
}

const pendingLabel = (count: number) => `, ${count} awaiting review`

/** Role-based sidebar navigation; renders nothing until the session is known. */
function AppNavigation() {
  const { user, loading, error } = useAuth()
  const pending = usePendingJournalAccessCount(!loading && !error && user?.role === 'DOCTOR')
  if (loading || error || !user) return null

  return (
    <nav className="side-nav" aria-label="Main">
      <p className="side-nav__title">Workspace</p>
      <ul className="side-nav__list">
        {itemsFor(user.role).map(({ to, label, icon: Icon, end, pendingCount }) => (
          <li key={to}>
            <NavLink to={to} end={end} className="side-nav__link">
              <Icon size={20} aria-hidden="true" />
              {label}
              {pendingCount && <CountBadge count={pending} label={pendingLabel} />}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}

export default AppNavigation
