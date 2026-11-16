import { LogIn, UserPlus, UserRound } from 'lucide-react'
import { Link } from 'react-router'
import { accountFullName } from '../../features/auth/accountName.ts'
import { useAuth } from '../../features/auth/AuthContext.ts'
import { roleLabels } from '../../features/auth/roleLabels.ts'
import Logo from '../brand/Logo.tsx'
import SignOutButton from './SignOutButton.tsx'

/** Top bar: logo, sign-in actions for visitors, account summary and sign-out for signed-in users. */
function AppHeader() {
  const { user, loading, error } = useAuth()
  // Name first and e-mail as secondary information; accounts without a stored name show their e-mail and role.
  const fullName = user ? accountFullName(user) : null

  return (
    <header className="app-header">
      <Logo />
      <nav className="app-header__actions" aria-label="Account">
        {!loading && !error && user && (
          <>
            <Link to="/login" className="user-chip"
              aria-label={`Your account: ${fullName ?? user.email}, ${roleLabels[user.role]}`}>
              <span className="user-chip__avatar" aria-hidden="true"><UserRound size={18} /></span>
              <span className="user-chip__text" aria-hidden="true">
                <span className="user-chip__email">{fullName ?? user.email}</span>
                <span className="user-chip__role">{fullName ? user.email : roleLabels[user.role]}</span>
              </span>
            </Link>
            <SignOutButton />
          </>
        )}
        {!loading && !error && !user && (
          <>
            <Link to="/login" className="btn btn--ghost btn--sm"><LogIn size={18} aria-hidden="true" />Sign in</Link>
            <Link to="/register" className="btn btn--primary btn--sm"><UserPlus size={18} aria-hidden="true" />Create account</Link>
          </>
        )}
        {!loading && error && (
          <Link to="/login" className="btn btn--ghost btn--sm"><LogIn size={18} aria-hidden="true" />Sign in</Link>
        )}
      </nav>
    </header>
  )
}

export default AppHeader
