import assert from 'node:assert/strict'
import test from 'node:test'
import {
  isFuturePersonnummer,
  isValidPersonnummer,
  normalizePersonnummer,
  personnummerBirthDate,
} from '../src/features/patientmanagement/personnummer.ts'
import { givenNames, localToday, validatePatientRegistration } from '../src/features/patientmanagement/patientValidation.ts'

// Fictional identities only. MediCore and MediPortal do not validate the Luhn check digit: a personnummer is valid if
// it has exactly 12 digits whose first eight are a real date. Invalid ones have a wrong length, characters or an
// impossible date.
const valid = {
  personnummer: '199001012385',
  family: 'Testsson',
  given: 'Tolvan Syntet',
  gender: 'FEMALE',
}
const today = '2026-11-08'

test('accepts valid personnummer and returns their date of birth', () => {
  for (const [personnummer, birthDate] of [
    ['191202119150', '1912-02-11'],
    ['199001012385', '1990-01-01'],
    ['198506152381', '1985-06-15'],
    ['200002292381', '2000-02-29'],
    ['197303159250', '1973-03-15'],
  ]) {
    assert.equal(personnummerBirthDate(personnummer), birthDate)
    assert.equal(isValidPersonnummer(personnummer), true)
  }
})

test('accepts any last four digits: the Luhn check digit is not validated', () => {
  for (const [personnummer, birthDate] of [
    ['191202119151', '1912-02-11'],
    ['199001012386', '1990-01-01'],
    ['200412201971', '2004-12-20'],
    ['199912319999', '1999-12-31'],
    ['200001010000', '2000-01-01'],
  ]) {
    assert.equal(personnummerBirthDate(personnummer), birthDate)
    assert.equal(isValidPersonnummer(personnummer), true)
  }
})

test('rejects formats and invalid dates', () => {
  for (const personnummer of [
    '202602301234', '200413401234', '12345', 'ABCDEFGHIJKL',
    '9001012385', '19900101-2385', '19900101+2385', ' 199001012385', '19900101238X', '1990010123850', '',
    // Month 13, day 32, 29 Feb in a non-leap year, coordination number (day + 60), day 00.
    '199013019808', '199001329805', '199902299826', '199001619809', '199001009803',
  ]) {
    assert.equal(personnummerBirthDate(personnummer), null, `accepted invalid value #${personnummer.length}`)
    assert.equal(isValidPersonnummer(personnummer), false)
  }
})

test('normalizes the 12-digit and the hyphenated form like MediCore', () => {
  assert.equal(normalizePersonnummer('199001012385', today), '199001012385')
  assert.equal(normalizePersonnummer('19900101-2385', today), '199001012385')
  assert.equal(normalizePersonnummer('  19900101-2385 ', today), '199001012385')
  assert.equal(normalizePersonnummer('20041220-1971', today), '200412201971')
  assert.equal(normalizePersonnummer('199001012386', today), '199001012386')
  for (const invalid of ['', '   ', '199002301234', '19900101+2385', '1990-0101-2385', '900101-2385', '19900101 2385',
    '1990010123850', '19900101-23850']) {
    assert.equal(normalizePersonnummer(invalid, today), null)
  }
  // Valid number, but born after today.
  assert.equal(normalizePersonnummer('202611092384', today), null)
  assert.equal(isFuturePersonnummer('202611092384', today), true)
  assert.equal(isFuturePersonnummer('20261109-2384', today), true)
  assert.equal(isFuturePersonnummer('202611082385', today), false)
  assert.equal(isFuturePersonnummer('199001012386', today), false)
  assert.equal(isFuturePersonnummer('203512201234', today), true)
})

test('accepts complete patient data without a date of birth', () => {
  assert.equal(validatePatientRegistration(valid, today), null)
  assert.equal(validatePatientRegistration({ ...valid, personnummer: '19900101-2385' }, today), null)
  // A wrong Luhn check digit does not prevent registration.
  assert.equal(validatePatientRegistration({ ...valid, personnummer: '200412201971' }, today), null)
  for (const gender of ['FEMALE', 'MALE', 'OTHER', 'UNKNOWN']) {
    assert.equal(validatePatientRegistration({ ...valid, gender }, today), null)
  }
  assert.equal(validatePatientRegistration({ ...valid, family: 'x'.repeat(100), given: 'Å'.repeat(100) }, today), null)
})

test('rejects invalid patient data without repeating it', () => {
  for (const fields of [
    { ...valid, personnummer: '' },
    { ...valid, personnummer: '199002301234' },
    { ...valid, personnummer: 'ABCDEFGHIJKL' },
    { ...valid, personnummer: '19900101+2385' },
    { ...valid, personnummer: '209901019803' },
    { ...valid, family: '' },
    { ...valid, family: '   ' },
    { ...valid, family: 'x'.repeat(101) },
    { ...valid, family: 'Test\u0007sson' },
    { ...valid, given: '' },
    { ...valid, given: '   ' },
    { ...valid, given: 'x'.repeat(101) },
    { ...valid, given: Array(11).fill('Tolvan').join(' ') },
    { ...valid, gender: '' },
    { ...valid, gender: 'female' },
  ]) {
    const message = validatePatientRegistration(fields, today)
    assert.ok(message, 'invalid data was accepted')
    assert.doesNotMatch(message, /\d{6}|Testsson|Tolvan|1990/)
  }
})

test('the date of birth in the personnummer may be today but not in the future', () => {
  assert.equal(validatePatientRegistration({ ...valid, personnummer: '202611082385' }, today), null)
  assert.match(validatePatientRegistration({ ...valid, personnummer: '202611092384' }, today) ?? '', /future/)
})

test('splits given names on whitespace and formats the local date', () => {
  assert.deepEqual(givenNames('  Tolvan   Syntet\tAnna-Karin '), ['Tolvan', 'Syntet', 'Anna-Karin'])
  assert.deepEqual(givenNames('   '), [])
  assert.equal(localToday(new Date(2026, 0, 5)), '2026-01-05')
})

test('the form accepts any mathematically valid fictional personnummer', () => {
  // Fictional numbers from the 1820s: valid date and check digit, not on Skatteverket's list, and impossible for any
  // living person (personnummer were introduced in 1947).
  for (const personnummer of ['182003151236', '182511044568']) {
    assert.equal(validatePatientRegistration({ ...valid, personnummer, family: 'Ångström-Pihl', given: 'Zelda' }, today), null)
  }
})
