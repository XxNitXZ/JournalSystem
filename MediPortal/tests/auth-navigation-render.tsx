import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import AppRoutes from '../src/app/AppRoutes.tsx'
import { AuthContext, type AuthState } from '../src/features/auth/AuthContext.ts'
import AuthProvider from '../src/features/auth/AuthProvider.tsx'

export function renderRoute(state: AuthState, path = '/patient') {
  return renderToStaticMarkup(
    <AuthContext value={state}>
      <MemoryRouter initialEntries={[path]}><AppRoutes /></MemoryRouter>
    </AuthContext>,
  )
}

export function renderInitialSession() {
  return renderToStaticMarkup(
    <AuthProvider>
      <MemoryRouter initialEntries={['/patient']}><AppRoutes /></MemoryRouter>
    </AuthProvider>,
  )
}
