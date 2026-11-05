import { Link, Navigate, Outlet } from 'react-router'
import { useAuth } from './AuthContext.ts'
import type { Role } from './types.ts'

function ProtectedRoute({ role }: { role: Role }) {
  const { user, loading, error } = useAuth()

  if (loading) return <p role="status">Checking your session…</p>
  if (error) return <p role="alert">{error} <Link to="/login">Sign in</Link></p>
  if (!user) return <Navigate to="/login" replace />
  if (user.role !== role) return <p role="alert">This page is available to {role.toLowerCase()} accounts only.</p>

  return <Outlet />
}

export default ProtectedRoute
