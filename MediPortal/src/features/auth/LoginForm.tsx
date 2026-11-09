import { useRef, useState, type SubmitEvent } from 'react'
import { LogIn } from 'lucide-react'
import { Link, Navigate } from 'react-router'
import AuthLayout from '../../components/layout/AuthLayout.tsx'
import Alert from '../../components/ui/Alert.tsx'
import FormField from '../../components/ui/FormField.tsx'
import LoadingState from '../../components/ui/LoadingState.tsx'
import AccountOverview from './AccountOverview.tsx'
import { useAuth } from './AuthContext.ts'
import { loginErrorMessage } from './loginErrorMessage.ts'
import { dashboardPath } from './roleLabels.ts'

function LoginForm() {
  const { user, loading, error: sessionError, signIn } = useAuth()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [signedInHere, setSignedInHere] = useState(false)
  const submitting = useRef(false)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting.current) return
    const form = event.currentTarget
    const fields = new FormData(form)
    const email = String(fields.get('email') ?? '')
    const password = String(fields.get('password') ?? '')
    setError('')
    if (!password.trim() || new TextEncoder().encode(password).length > 72) {
      setError('Enter a password of up to 72 characters. Some special characters count as more than one.')
      return
    }
    submitting.current = true
    setPending(true)
    try {
      await signIn({ email, password })
      form.reset()
      setSignedInHere(true)
    } catch (failure: unknown) {
      setError(loginErrorMessage(failure))
    } finally {
      submitting.current = false
      setPending(false)
    }
  }

  if (loading) return <LoadingState label="Checking your session…" />
  // Right after signing in, continue to the dashboard; otherwise /login shows the account page.
  if (user && signedInHere) return <Navigate to={dashboardPath(user.role)} replace />
  if (user) return <AccountOverview user={user} />

  return (
    <AuthLayout titleId="login-title">
      <div>
        <h1 id="login-title">Sign in</h1>
        <p className="auth__lead">Welcome back. Sign in with your email address and password.</p>
      </div>
      <form className="form" onSubmit={submit} aria-busy={pending}>
        <fieldset disabled={pending}>
          <legend className="visually-hidden">Login details</legend>
          <FormField htmlFor="login-email" label="Email">
            <input id="login-email" name="email" type="email" autoComplete="username" maxLength={255} required />
          </FormField>
          <FormField htmlFor="login-password" label="Password">
            <input id="login-password" name="password" type="password" autoComplete="current-password" required />
          </FormField>
          <button className="btn btn--primary btn--block" type="submit">
            <LogIn size={18} aria-hidden="true" />
            {pending ? 'Signing in…' : 'Sign in'}
          </button>
        </fieldset>
      </form>
      <Alert tone="error" role="alert">{error || sessionError}</Alert>
      {!sessionError && (
        <p className="form__footer">New to MediCore? <Link to="/register">Create an account</Link></p>
      )}
    </AuthLayout>
  )
}

export default LoginForm
