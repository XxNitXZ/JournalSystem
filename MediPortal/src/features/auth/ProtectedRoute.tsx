import { Link, Navigate, Outlet } from 'react-router'
import LoadingState from '../../components/ui/LoadingState.tsx'
import { useAuth } from './AuthContext.ts'
import type { Role } from './types.ts'

/** Renders its child routes only for a signed-in user with one of the allowed roles (role or roles). */
function ProtectedRoute({ role, roles }: { role?: Role; roles?: readonly Role[] }) {
  const { user, loading, error } = useAuth()
  const allowed = roles ?? (role ? [role] : [])

  if (loading) return <LoadingState label="Checking your session…" />
  if (error) return <p role="alert" className="alert alert--error">{error} <Link to="/login">Sign in</Link></p>
  if (!user) return <Navigate to="/login" replace />
  if (!allowed.includes(user.role)) {
    const names = allowed.map((value) => value.toLowerCase()).join(' or ')
    return <p role="alert" className="alert alert--error">This page is available to {names} accounts only.</p>
  }

  return <Outlet />
}

export default ProtectedRoute
