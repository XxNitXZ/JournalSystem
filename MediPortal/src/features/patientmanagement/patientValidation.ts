import { isFuturePersonnummer, localToday, normalizePersonnummer } from './personnummer.ts'
import { genders } from './types.ts'

export { localToday }

export interface PatientRegistrationFields {
  personnummer: string
  family: string
  /** Given names separated by spaces. */
  given: string
  gender: string
}

const MAX_NAME_LENGTH = 100
const MAX_GIVEN_NAMES = 10

/** Given names as entered, split on whitespace. */
export function givenNames(value: string): string[] {
  return value.trim().split(/\s+/).filter(Boolean)
}

function validName(value: string): boolean {
  const name = value.trim()
  // eslint-disable-next-line no-control-regex
  return name.length > 0 && name.length <= MAX_NAME_LENGTH && !/[\u0000-\u001f\u007f-\u009f]/.test(name)
}

/**
 * The first problem with the entered patient data, or null. The date of birth is not entered: MediCore derives
 * it from the personnummer. Messages never repeat the entered values.
 */
export function validatePatientRegistration(fields: PatientRegistrationFields, today = localToday()): string | null {
  if (isFuturePersonnummer(fields.personnummer, today)) {
    return 'The date of birth in the personnummer cannot be in the future.'
  }
  if (!normalizePersonnummer(fields.personnummer, today)) {
    return 'Enter a valid personnummer: 12 digits (YYYYMMDDNNNN or YYYYMMDD-NNNN) starting with a real date of birth.'
  }
  if (!validName(fields.family)) {
    return `Enter a family name of at most ${MAX_NAME_LENGTH} characters.`
  }
  const given = givenNames(fields.given)
  if (given.length === 0 || given.length > MAX_GIVEN_NAMES || !given.every(validName)) {
    return `Enter between 1 and ${MAX_GIVEN_NAMES} given names of at most ${MAX_NAME_LENGTH} characters each.`
  }
  if (!genders.some((gender) => gender === fields.gender)) {
    return 'Select a gender.'
  }
  return null
}
