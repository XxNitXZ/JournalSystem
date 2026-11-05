import { useEffect, useRef, useState, type SubmitEvent } from 'react'
import { getCurrentUser, login, logout } from './authenticationService.ts'
import { loginErrorMessage } from './loginErrorMessage.ts'
import type { UserDto } from './types.ts'

function LoginForm() {
  const [user, setUser] = useState<UserDto | null>(null)
  const [loading, setLoading] = useState(true)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const submitting = useRef(false)

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

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const form = event.currentTarget
    const fields = new FormData(form)
    const email = String(fields.get('email') ?? '')
    const password = String(fields.get('password') ?? '')
    setError('')
    setMessage('')
    if (!password.trim() || new TextEncoder().encode(password).length > 72) {
      setError('Enter a nonblank password of at most 72 UTF-8 bytes.')
      return
    }
    submitting.current = true
    setPending(true)
    try {
      const authenticated = await login({ email, password })
      form.reset()
      setUser(authenticated)
      setMessage('You are signed in.')
    } catch (failure: unknown) {
      setError(loginErrorMessage(failure))
    } finally {
      submitting.current = false
      setPending(false)
    }
  }

  async function signOut() {
    if (submitting.current) return
    submitting.current = true
    setPending(true)
    setError('')
    setMessage('')
    try {
      await logout()
      setUser(null)
      setMessage('You are signed out.')
    } catch {
      setError('Unable to confirm logout. Please try again.')
    } finally {
      submitting.current = false
      setPending(false)
    }
  }

  return (
    <section className="page" aria-labelledby="login-title">
      <h1 id="login-title">{user ? 'Your account' : 'Sign in'}</h1>
      {loading ? <p role="status">Checking your session…</p> : user ? (
        <div className="registration-form">
          <p>Signed in as <strong>{user.email}</strong></p>
          <p>Role: {user.role}</p>
          <button className="button-link" type="button" disabled={pending} onClick={() => void signOut()}>
            {pending ? 'Signing out…' : 'Sign out'}
          </button>
        </div>
      ) : (
        <form className="registration-form" onSubmit={submit} aria-busy={pending}>
          <fieldset disabled={pending}>
            <legend>Login details</legend>
            <label htmlFor="login-email">Email</label>
            <input id="login-email" name="email" type="email" autoComplete="username" maxLength={255} required />
            <label htmlFor="login-password">Password</label>
            <input id="login-password" name="password" type="password" autoComplete="current-password" required />
            <button className="button-link" type="submit">{pending ? 'Signing in…' : 'Sign in'}</button>
          </fieldset>
        </form>
      )}
      <p role="alert" className="registration-error">{error}</p>
      <p role="status">{message}</p>
    </section>
  )
}

export default LoginForm
