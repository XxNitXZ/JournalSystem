import { Outlet } from 'react-router'
import { useAuth } from '../../features/auth/AuthContext.ts'
import AppHeader from './AppHeader.tsx'
import AppNavigation from './AppNavigation.tsx'

/** Header, role-based sidebar (signed-in users only), main content and footer. */
function AppLayout() {
  const { user, loading, error } = useAuth()
  const signedIn = !loading && !error && user !== null

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>
      <AppHeader />
      <div className={signedIn ? 'app-body' : 'app-body app-body--public'}>
        {signedIn && (
          <aside className="app-sidebar">
            <AppNavigation />
          </aside>
        )}
        <main id="main-content" className="app-main" tabIndex={-1}>
          <Outlet />
        </main>
      </div>
      <footer className="app-footer">
        <p>MediCore · Demonstration environment. Please do not enter real patient information.</p>
      </footer>
    </div>
  )
}

export default AppLayout
