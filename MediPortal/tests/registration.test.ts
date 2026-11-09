import assert from 'node:assert/strict'
import test from 'node:test'
import { validateRegistration } from '../src/features/auth/validation.ts'

test('accepts all supported roles', () => {
  for (const role of ['PATIENT', 'DOCTOR', 'STAFF']) {
    assert.equal(validateRegistration('user@example.com', 'password', 'password', role), null)
  }
})

test('rejects missing fields, invalid email, roles, and mismatched confirmation', () => {
  for (const fields of [
    ['', 'password', 'password', 'PATIENT'],
    ['invalid', 'password', 'password', 'PATIENT'],
    ['a b@example.com', 'password', 'password', 'PATIENT'],
    ['a'.repeat(244) + '@example.com', 'password', 'password', 'PATIENT'],
    ['user@example.com', '', '', 'PATIENT'],
    ['user@example.com', '   ', '   ', 'PATIENT'],
    ['user@example.com', 'password', '', 'PATIENT'],
    ['user@example.com', 'password', 'different', 'PATIENT'],
    ['user@example.com', 'password', 'password', ''],
    ['user@example.com', 'password', 'password', 'ADMIN'],
  ]) {
    assert.ok(validateRegistration(fields[0], fields[1], fields[2], fields[3]))
  }
})

test('enforces the BCrypt UTF-8 byte limit without trimming passwords', () => {
  for (const password of ['x'.repeat(72), 'é'.repeat(36), ' password ']) {
    assert.equal(validateRegistration('user@example.com', password, password, 'PATIENT'), null)
  }
  for (const password of ['x'.repeat(73), 'é'.repeat(37)]) {
    assert.ok(validateRegistration('user@example.com', password, password, 'PATIENT'))
  }
})

test('requires a first and last name with the same rules as MediCore', async () => {
  const { validateAccountNames } = await import('../src/features/auth/validation.ts')
  for (const [given, family] of [['Anna', 'Testsson'], [' Anna-Karin ', 'von Testsson'], ['Å'.repeat(100), 'Ö'.repeat(100)],
    ['𝒜'.repeat(100), 'Testsson']]) {
    assert.equal(validateAccountNames(given, family), null)
  }
  for (const [given, family, expected] of [
    ['', 'Testsson', /first name/], ['   ', 'Testsson', /first name/], ['x'.repeat(101), 'Testsson', /first name/],
    ['An\nna', 'Testsson', /first name/], ['Anna', '', /last name/], ['Anna', '\t', /last name/],
    ['Anna', 'x'.repeat(101), /last name/], ['Anna', 'Test\u0085sson', /last name/],
  ] as const) {
    const message = validateAccountNames(given, family)
    assert.match(message ?? '', expected)
    assert.doesNotMatch(message ?? '', /Anna|Testsson|xxx/)
  }
})

test('requires a valid personnummer for every account, like MediCore', async () => {
  const { validateAccountPersonnummer } = await import('../src/features/auth/validation.ts')
  const today = '2026-11-08'
  // Fictional numbers; the Luhn check digit is not validated (182003151237, 200412201971 have wrong ones).
  for (const value of ['182003151236', '18200315-1236', ' 199001012385 ', '202611082385', '182003151237',
    '200412201971', '199912319999', '200001010000']) {
    assert.equal(validateAccountPersonnummer(value, today), null, value)
  }
  assert.equal(validateAccountPersonnummer('', today), 'Enter your personnummer.')
  assert.equal(validateAccountPersonnummer('   ', today), 'Enter your personnummer.')
  // 30 February, month 13 and day 40, 10 and 13 digits, other separators, letters.
  for (const value of ['202602301234', '200413401234', '1820031512', '1820031512360', '18200315+1236', 'abc',
    'ABCDEFGHIJKL', '199013019808']) {
    const message = validateAccountPersonnummer(value, today)
    assert.match(message ?? '', /valid personnummer/)
    assert.doesNotMatch(message ?? '', /1820|1990/)
  }
  assert.match(validateAccountPersonnummer('202611092384', today) ?? '', /future/)
  assert.match(validateAccountPersonnummer('203512201234', today) ?? '', /future/)
  assert.doesNotMatch(validateAccountPersonnummer('182003151237x', today) ?? '', /check digit/)
})
