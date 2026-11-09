import { useRef, useState } from 'react'
import { LogOut } from 'lucide-react'
import { useNavigate } from 'react-router'
import { useAuth } from '../../features/auth/AuthContext.ts'

/** Signs out through the existing logout endpoint and returns to the sign-in page. */
function SignOutButton() {
  const { signOut } = useAuth()
  const navigate = useNavigate()
  const [pending, setPending] = useState(false)
  const [failed, setFailed] = useState(false)
  const submitting = useRef(false)

  async function handleClick() {
    if (submitting.current) return
    submitting.current = true
    setPending(true)
    setFailed(false)
    try {
      await signOut()
      navigate('/login')
    } catch {
      setFailed(true)
    } finally {
      submitting.current = false
      setPending(false)
    }
  }

  return (
    <>
      <button type="button" className="btn btn--secondary btn--sm" onClick={() => void handleClick()} disabled={pending}
        title={failed ? 'Sign-out could not be confirmed. Try again.' : undefined}>
        <LogOut size={18} aria-hidden="true" />
        {pending ? 'Signing out…' : failed ? 'Retry sign out' : 'Sign out'}
      </button>
      {failed && <span role="alert" className="visually-hidden">Sign-out could not be confirmed. Try again.</span>}
    </>
  )
}

export default SignOutButton
