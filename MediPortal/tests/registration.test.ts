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
