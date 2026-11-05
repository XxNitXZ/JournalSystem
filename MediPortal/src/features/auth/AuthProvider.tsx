import { useEffect, useState, type ReactNode } from 'react'
import { AuthContext } from './AuthContext.ts'
import { getCurrentUser, login, logout } from './authenticationService.ts'
import type { LoginRequest, UserDto } from './types.ts'

function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserDto | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    const controller = new AbortController()
    getCurrentUser({ signal: controller.signal })
      .then((current) => { if (!controller.signal.aborted) setUser(current) })
      .catch(() => {
        if (!controller.signal.aborted) setError('Unable to check your session. Please try signing in.')
      })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [])

  async function signIn(request: LoginRequest) {
    const authenticated = await login(request)
    setUser(authenticated)
    setError('')
  }

  async function signOut() {
    await logout()
    setUser(null)
    setError('')
  }

  return (
    <AuthContext value={{ user, loading, error, signIn, signOut }}>
      {children}
    </AuthContext>
  )
}

export default AuthProvider
