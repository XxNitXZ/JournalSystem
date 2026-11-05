export const roles = ['PATIENT', 'DOCTOR', 'STAFF'] as const
export type Role = (typeof roles)[number]

export interface RegistrationRequest {
  email: string
  password: string
  role: Role
}

export interface RegistrationResponse {
  id: number
  email: string
  role: Role
}
