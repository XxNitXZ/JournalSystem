import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('patient registration route and navigation', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  try {
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const state = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {} }
    const account = (role: string) => ({ ...state, user: { id: 42, email: 'user@example.com', role } })

    for (const role of ['DOCTOR', 'STAFF']) {
      await context.test(`${role} sees the link and the registration form`, () => {
        const html = renderRoute(account(role), '/patients/new')
        assert.ok(html.includes('href="/patients/new"'))
        assert.match(html, /<h1 id="patient-registration-title">Register patient<\/h1>/)
        for (const field of ['personnummer', 'family', 'given', 'gender']) {
          assert.ok(html.includes(`name="${field}"`), `missing field ${field}`)
        }
        // The date of birth is derived from the personnummer, not entered.
        assert.ok(!html.includes('name="birthDate"'))
        assert.match(html, /date of birth is taken from the personnummer/)
        assert.match(html, /role="alert"/)
        assert.match(html, /role="status"/)
        const home = renderRoute(account(role), `/${role.toLowerCase()}`)
        assert.match(home, /Register a patient/)
      })
    }

    await context.test('PATIENT accounts see neither the link nor the form', () => {
      const html = renderRoute(account('PATIENT'), '/patients/new')
      assert.ok(!html.includes('href="/patients/new"'))
      assert.match(html, /available to doctor or staff accounts only/)
      assert.doesNotMatch(html, /Patient details|name="personnummer"/)
    })

    await context.test('anonymous users, loading and session errors never render the form', () => {
      for (const html of [
        renderRoute(state, '/patients/new'),
        renderRoute({ ...account('STAFF'), loading: true }, '/patients/new'),
        renderRoute({ ...account('DOCTOR'), error: 'Session unavailable.' }, '/patients/new'),
      ]) {
        assert.doesNotMatch(html, /Patient details|name="personnummer"/)
        assert.ok(!html.includes('href="/patients/new"'))
      }
    })
  } finally {
    await server.close()
  }
})
