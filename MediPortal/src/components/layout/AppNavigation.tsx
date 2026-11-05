import { NavLink } from 'react-router'
import { useAuth } from '../../features/auth/AuthContext.ts'

function AppNavigation() {
  const { user, loading, error } = useAuth()
  return (
    <nav className="app-nav" aria-label="Main">
      <ul className="app-nav__list">
        {!loading && !error && user?.role === 'PATIENT' && (
          <li><NavLink to="/patient" className="app-nav__link">Patient home</NavLink></li>
        )}
        {!loading && !error && user?.role === 'DOCTOR' && (
          <li><NavLink to="/doctor" className="app-nav__link">Doctor home</NavLink></li>
        )}
        {!loading && !error && user?.role === 'STAFF' && (
          <li><NavLink to="/staff" className="app-nav__link">Staff home</NavLink></li>
        )}
        <li>
          <NavLink to="/" end className="app-nav__link">
            Home
          </NavLink>
        </li>
        <li>
          <NavLink to="/register" className="app-nav__link">Register</NavLink>
        </li>
        <li>
          <NavLink to="/login" className="app-nav__link">Sign in / Account</NavLink>
        </li>
      </ul>
    </nav>
  )
}

export default AppNavigation
