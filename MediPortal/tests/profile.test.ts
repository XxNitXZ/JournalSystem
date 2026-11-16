import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

// Fictional account only.
const user = { id: 7, email: 'anna@example.test', role: 'PATIENT', givenName: 'Anna', familyName: 'Testsson' }
const profile = {
  givenName: 'Anna', familyName: 'Testsson', personnummer: '200412201971', email: 'anna@example.test',
  phoneNumber: '+46701234567', role: 'PATIENT',
}

test('account management: read-only identity, contact details and password', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const service = await server.ssrLoadModule('/src/features/auth/profileService.ts')
    const forms = await server.ssrLoadModule('/src/features/auth/profileForm.ts')
    const phone = await server.ssrLoadModule('/src/features/auth/phoneNumber.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const { renderAccount } = await server.ssrLoadModule('/tests/account-render.tsx')
    const state = { user, loading: false, error: '', signIn: async () => {}, signOut: async () => {}, acceptSession: () => {} }

    function recordCalls(response: unknown, status = 200) {
      const calls: { method: string; path: string; headers: Record<string, string>; body?: string }[] = []
      globalThis.fetch = async (url, options) => {
        calls.push({ method: String(options?.method), path: String(url).slice(API_BASE_URL.length),
          headers: options?.headers as Record<string, string>, body: options?.body as string | undefined })
        assert.equal(options?.credentials, 'include')
        if (String(url).endsWith('/api/auth/csrf')) {
          return new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
        }
        return status === 204 ? new Response(null, { status }) : new Response(JSON.stringify(response), { status })
      }
      return calls
    }

    await context.test('the account page shows name and personnummer read-only, without editing them', () => {
      const html = renderAccount(state, user, null, profile)
      assert.match(html, /<dt>First name<\/dt><dd>Anna<\/dd><dt>Last name<\/dt><dd>Testsson<\/dd>/)
      assert.match(html, /<dt>Personnummer<\/dt><dd>20041220-1971<\/dd>/)
      assert.match(html, /cannot be changed after registration/)
      assert.match(html, /<dt>Email<\/dt><dd>anna@example\.test<\/dd><dt>Phone<\/dt><dd>\+46701234567<\/dd>/)
      assert.match(html, /Edit contact information<\/button>/)
      assert.match(html, /Change password<\/button>/)
      assert.doesNotMatch(html, /Edit profile/)
      for (const field of ['givenName', 'familyName', 'personnummer', 'role', 'email', 'phoneNumber', 'password']) {
        assert.ok(!html.includes(`name="${field}"`), `editable ${field}`)
      }
      // Without a phone number and personnummer (older accounts) nothing is invented.
      const older = renderAccount(state, { ...user, givenName: null, familyName: null }, null,
        { ...profile, givenName: null, familyName: null, personnummer: null, phoneNumber: null })
      assert.match(older, /<dt>Personnummer<\/dt><dd>Not on file<\/dd>/)
      assert.match(older, /<dt>Phone<\/dt><dd>Not provided<\/dd>/)
      // While loading, no account data is guessed.
      assert.match(renderAccount(state, user), /<dt>Personnummer<\/dt><dd>Loading…<\/dd>/)
    })

    await context.test('the contact form edits only e-mail and phone, with the current password', () => {
      const html = renderAccount(state, user, 'contact', profile)
      assert.match(html, /<input[^>]*value="anna@example\.test"[^>]*name="email"|<input[^>]*name="email"[^>]*value="anna@example\.test"/)
      assert.match(html, /<input[^>]*value="\+46701234567"[^>]*name="phoneNumber"|<input[^>]*name="phoneNumber"[^>]*value="\+46701234567"/)
      assert.match(html, /name="currentPassword"/)
      assert.match(html, /Required only when you change your email address/)
      assert.doesNotMatch(html, /name="givenName"|name="familyName"|name="personnummer"|name="role"/)
      // Identity stays visible and read-only.
      assert.match(html, /<dd>20041220-1971<\/dd>/)
    })

    await context.test('the password form asks for current, new and confirmed password', () => {
      const html = renderAccount(state, user, 'password', profile)
      for (const field of ['currentPassword', 'newPassword', 'confirmPassword']) {
        assert.match(html, new RegExp(`type="password"[^>]*name="${field}"|name="${field}"[^>]*type="password"`))
      }
      assert.match(html, /Confirm new password/)
    })

    await context.test('phone numbers are normalized like MediCore without changing digits', () => {
      assert.deepEqual(phone.normalizePhoneNumber(' 070-123 45 67 '), { ok: true, value: '0701234567' })
      assert.deepEqual(phone.normalizePhoneNumber('+44 (20) 7946 0958'), { ok: true, value: '+442079460958' })
      assert.deepEqual(phone.normalizePhoneNumber('0046 70 123 45 67'), { ok: true, value: '0046701234567' })
      assert.deepEqual(phone.normalizePhoneNumber(''), { ok: true, value: null })
      assert.deepEqual(phone.normalizePhoneNumber(null), { ok: true, value: null })
      for (const invalid of ['12345', '0701234567890123456', '070 ABC', '+46+70', '070/1234567', '++46701234567',
        '1'.repeat(33), '+']) {
        assert.deepEqual(phone.normalizePhoneNumber(invalid), { ok: false }, invalid)
      }
    })

    await context.test('a contact change sends only contact fields, and the e-mail only with the password', () => {
      const phoneOnly = forms.contactUpdate({ email: 'ANNA@example.test', phoneNumber: '070-123 45 67', currentPassword: '' },
        'anna@example.test')
      assert.deepEqual(phoneOnly, { ok: true, update: { phoneNumber: '0701234567' } })
      assert.deepEqual(forms.contactUpdate({ email: 'anna@example.test', phoneNumber: ' ', currentPassword: '' },
        'anna@example.test'), { ok: true, update: { phoneNumber: null } })
      const withEmail = forms.contactUpdate({ email: ' new@example.test ', phoneNumber: '', currentPassword: 'secret' },
        'anna@example.test')
      assert.deepEqual(withEmail, { ok: true, update: { phoneNumber: null, email: 'new@example.test', currentPassword: 'secret' } })
      assert.match(forms.contactUpdate({ email: 'new@example.test', phoneNumber: '', currentPassword: '' },
        'anna@example.test').error, /current password/)
      assert.match(forms.contactUpdate({ email: 'not-an-email', phoneNumber: '', currentPassword: 'x' },
        'anna@example.test').error, /valid email/)
      assert.match(forms.contactUpdate({ email: 'anna@example.test', phoneNumber: '12', currentPassword: '' },
        'anna@example.test').error, /valid phone number/)
    })

    await context.test('saving a new e-mail updates the signed-in user at once', async () => {
      const accepted: unknown[] = []
      const result = await forms.saveContact({ email: 'new@example.test', phoneNumber: '', currentPassword: 'secret' }, user, {
        update: async () => ({ ...profile, email: 'new@example.test', phoneNumber: null }),
        accept: (next: unknown) => accepted.push(next),
      })
      assert.equal(result.ok, true)
      assert.deepEqual(accepted, [{ ...user, email: 'new@example.test' }])

      // A phone-only change keeps the signed-in user as is; failures change nothing.
      const unchanged: unknown[] = []
      await forms.saveContact({ email: user.email, phoneNumber: '0701234567', currentPassword: '' }, user, {
        update: async () => profile, accept: (next: unknown) => unchanged.push(next),
      })
      assert.deepEqual(unchanged, [])
      const failed = await forms.saveContact({ email: 'new@example.test', phoneNumber: '', currentPassword: 'wrong' }, user, {
        update: async () => { throw new ApiError(403, 'x', '/api/auth/profile', 'CURRENT_PASSWORD_INVALID') },
        accept: () => assert.fail('must not update the session'),
      })
      assert.deepEqual(failed, { ok: false, error: 'Your current password is not correct.' })
    })

    await context.test('the API calls use the session, CSRF and only the allowed fields', async () => {
      let calls = recordCalls(profile)
      assert.deepEqual(await service.getAccountProfile(), profile)
      assert.deepEqual(calls.map((call) => `${call.method} ${call.path}`), ['GET /api/auth/profile'])

      calls = recordCalls(profile)
      await service.updateAccountContact({ phoneNumber: '0701234567' })
      assert.deepEqual(calls.map((call) => `${call.method} ${call.path}`), ['GET /api/auth/csrf', 'PATCH /api/auth/profile'])
      assert.equal(calls[1].headers['X-CSRF-TOKEN'], 'csrf-token')
      assert.deepEqual(JSON.parse(calls[1].body ?? ''), { phoneNumber: '0701234567' })

      calls = recordCalls(null, 204)
      const change = { currentPassword: 'old', newPassword: 'New-Passw0rd', confirmPassword: 'New-Passw0rd' }
      assert.equal(await service.changePassword(change), undefined)
      assert.deepEqual(calls.map((call) => `${call.method} ${call.path}`), ['GET /api/auth/csrf', 'POST /api/auth/password'])
      assert.deepEqual(JSON.parse(calls[1].body ?? ''), change)
    })

    await context.test('password changes are validated and errors never repeat input', () => {
      const valid = { currentPassword: 'old', newPassword: 'New-Passw0rd ', confirmPassword: 'New-Passw0rd ' }
      assert.equal(forms.validatePasswordChange(valid), null)
      assert.match(forms.validatePasswordChange({ ...valid, currentPassword: '' }), /current password/)
      assert.match(forms.validatePasswordChange({ ...valid, newPassword: '  ', confirmPassword: '  ' }), /new password/)
      assert.match(forms.validatePasswordChange({ ...valid, newPassword: 'å'.repeat(37), confirmPassword: 'å'.repeat(37) }),
        /72 characters/)
      assert.match(forms.validatePasswordChange({ ...valid, confirmPassword: 'other' }), /must match/)

      const message = (status: number, code?: string) => service.accountErrorMessage(new ApiError(status, 'x', '/api/auth', code))
      assert.match(message(403, 'CURRENT_PASSWORD_INVALID'), /current password is not correct/)
      assert.match(message(409, 'EMAIL_TAKEN'), /already used by another account/)
      assert.match(message(400, 'PHONE_INVALID'), /valid phone number/)
      assert.match(message(400, 'PASSWORD_MISMATCH'), /must match/)
      assert.match(message(400, 'FIELD_NOT_EDITABLE'), /cannot be changed/)
      assert.match(message(401), /Sign in again/)
      assert.match(message(500), /could not be saved/)
      assert.match(service.accountErrorMessage(new TypeError('Failed to fetch')), /Unable to reach MediCore/)
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
