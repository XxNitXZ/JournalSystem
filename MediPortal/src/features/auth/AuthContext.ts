import { createContext, useContext } from 'react'
import type { LoginRequest, UserDto } from './types.ts'

export interface AuthState {
  user: UserDto | null
  loading: boolean
  error: string
  signIn: (request: LoginRequest) => Promise<void>
  signOut: () => Promise<void>
  /** Adopts a session that MediCore has already started, e.g. after registering a PATIENT account. */
  acceptSession: (user: UserDto) => void
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth(): AuthState {
  const state = useContext(AuthContext)
  if (!state) throw new Error('useAuth requires AuthProvider')
  return state
}
