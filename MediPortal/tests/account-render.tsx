import { renderToStaticMarkup } from 'react-dom/server'
import { MemoryRouter } from 'react-router'
import { AuthContext, type AuthState } from '../src/features/auth/AuthContext.ts'
import AccountOverview, { type AccountPanel } from '../src/features/auth/AccountOverview.tsx'
import type { AccountProfile } from '../src/features/auth/profileService.ts'
import type { UserDto } from '../src/features/auth/types.ts'

export function renderAccount(state: AuthState, user: UserDto, panel: AccountPanel = null, profile?: AccountProfile) {
  return renderToStaticMarkup(
    <AuthContext value={state}>
      <MemoryRouter><AccountOverview user={user} initialPanel={panel} initialProfile={profile} /></MemoryRouter>
    </AuthContext>,
  )
}
