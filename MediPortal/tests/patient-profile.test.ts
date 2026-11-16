import assert from 'node:assert/strict'
import test from 'node:test'
import { isValidPatientId } from '../src/features/patientmanagement/patientId.ts'
import {
  displayBirthDate,
  displayGender,
  displayName,
  displayPersonnummer,
  NOT_RECORDED,
  PERSONNUMMER_SYSTEM,
} from '../src/features/patientmanagement/patientProfileFormat.ts'

// Synthetic test data only (Skatteverket's test personnummer series).
const patient = {
  id: 'syn-profile-41',
  identifiers: [
    { system: 'urn:synthetic:other', value: 'other-1' },
    { system: PERSONNUMMER_SYSTEM, value: '199001012385' },
  ],
  names: [
    { use: 'nickname', text: 'Tolle', family: null, given: [] },
    { use: 'official', text: null, family: 'Testsson', given: ['Tolvan', 'Syntet'] },
  ],
  gender: 'female',
  birthDate: '1990-01-01',
}
const empty = { id: 'syn-empty-1', identifiers: [], names: [], gender: null, birthDate: null }

test('accepts only logical FHIR Patient IDs', () => {
  for (const id of ['local-1', 'syn-profile-41', 'A.b-9', 'x'.repeat(64)]) {
    assert.equal(isValidPatientId(id), true, id)
  }
  for (const id of ['', ' ', 'bad_id', 'Patient/1', '../1', 'id?x=1', 'id#x', 'ä', ' local-1', 'x'.repeat(65)]) {
    assert.equal(isValidPatientId(id), false, `accepted #${id.length}`)
  }
})

test('formats a complete profile', () => {
  assert.equal(displayName(patient), 'Tolvan Syntet Testsson')
  assert.equal(displayPersonnummer(patient), '19900101-2385')
  assert.equal(displayGender(patient), 'Female')
  assert.equal(displayBirthDate(patient), '1990-01-01')
})

test('shows "Not recorded" for missing data instead of failing', () => {
  assert.equal(displayName(empty), NOT_RECORDED)
  assert.equal(displayPersonnummer(empty), NOT_RECORDED)
  assert.equal(displayGender(empty), NOT_RECORDED)
  assert.equal(displayBirthDate(empty), NOT_RECORDED)
  assert.equal(displayName({ ...empty, names: [{ use: null, text: null, family: null, given: [] }] }), NOT_RECORDED)
})

test('falls back to the first name, its text, and unformatted identifiers', () => {
  assert.equal(displayName({ ...empty, names: [{ use: null, text: 'Synthetic Text', family: null, given: [] }] }),
    'Synthetic Text')
  assert.equal(displayName({ ...empty, names: [{ use: 'usual', text: null, family: 'Testsson', given: [] }] }),
    'Testsson')
  // Only the personnummer system counts; other identifiers are not shown as a personnummer.
  assert.equal(displayPersonnummer({ ...empty, identifiers: [{ system: 'urn:synthetic:other', value: '199001012385' }] }),
    NOT_RECORDED)
  assert.equal(displayPersonnummer({ ...empty, identifiers: [{ system: PERSONNUMMER_SYSTEM, value: 'syn-123' }] }),
    'syn-123')
  assert.equal(displayGender({ ...empty, gender: 'other' }), 'Other')
  assert.equal(displayGender({ ...empty, gender: 'unknown' }), 'Unknown')
})
