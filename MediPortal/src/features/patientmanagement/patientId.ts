/** A logical FHIR Patient ID as accepted by MediCore: 1–64 ASCII letters, digits, '-' or '.'. */
export function isValidPatientId(value: string): boolean {
  return /^[A-Za-z0-9.-]{1,64}$/.test(value)
}
