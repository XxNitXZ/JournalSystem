import type { Role } from './types.ts'

/** Human-readable role names; the API values (PATIENT, DOCTOR, STAFF) are unchanged. */
export const roleLabels: Record<Role, string> = {
  PATIENT: 'Patient',
  DOCTOR: 'Doctor',
  STAFF: 'Staff',
}

/** The dashboard route of a role, e.g. /doctor. */
export function dashboardPath(role: Role): string {
  return `/${role.toLowerCase()}`
}
