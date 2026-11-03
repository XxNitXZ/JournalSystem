import { Outlet } from 'react-router'
import AppHeader from './AppHeader.tsx'
import AppNavigation from './AppNavigation.tsx'

function AppLayout() {
  return (
    <div className="app-layout">
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>
      <div className="app-topbar">
        <AppHeader />
        <AppNavigation />
      </div>
      <main id="main-content" className="app-main" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}

export default AppLayout
