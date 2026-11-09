import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('patient profile route, view and navigation', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const { renderProfile } = await server.ssrLoadModule('/tests/patient-profile-render.tsx')
    const state = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {} }
    const account = (role: string) => ({ ...state, user: { id: 42, email: 'user@example.com', role } })
    // Server rendering never runs effects, so no profile request may be sent while rendering routes.
    globalThis.fetch = async () => { throw new Error('unexpected request') }

    for (const role of ['DOCTOR', 'STAFF']) {
      await context.test(`${role} gets the profile page and can open a profile from home`, () => {
        const html = renderRoute(account(role), '/patients/syn-profile-41')
        assert.match(html, /<h1 id="patient-profile-title">Patient profile<\/h1>/)
        assert.match(html, /Loading patient profile/)
        assert.ok(html.includes(`href="/${role.toLowerCase()}"`))
        const home = renderRoute(account(role), `/${role.toLowerCase()}`)
        assert.match(home, /Find a patient/)
        assert.ok(home.includes('name="query"'))
        assert.match(home, /role="search"/)
        // The technical ID input has been replaced by the search.
        assert.ok(!home.includes('name="patientId"'))
        assert.match(home, /Register a patient/)
      })
    }

    await context.test('an invalid ID is rejected without loading anything', () => {
      const html = renderRoute(account('DOCTOR'), '/patients/bad_id')
      assert.match(html, /The patient ID is not valid/)
      assert.doesNotMatch(html, /Loading patient profile/)
    })

    await context.test('the registration route still wins over the profile route', () => {
      const html = renderRoute(account('STAFF'), '/patients/new')
      assert.match(html, /Register patient/)
      assert.doesNotMatch(html, /Patient profile/)
    })

    await context.test('PATIENT accounts, anonymous users and session errors never see a profile', () => {
      const patient = renderRoute(account('PATIENT'), '/patients/syn-profile-41')
      assert.match(patient, /available to doctor or staff accounts only/)
      assert.doesNotMatch(patient, /Patient profile|Loading patient profile/)
      assert.doesNotMatch(renderRoute(account('PATIENT'), '/patient'), /Find a patient|name="query"/)
      for (const html of [
        renderRoute(state, '/patients/syn-profile-41'),
        renderRoute({ ...account('DOCTOR'), loading: true }, '/patients/syn-profile-41'),
        renderRoute({ ...account('STAFF'), error: 'Session unavailable.' }, '/patients/syn-profile-41'),
      ]) {
        assert.doesNotMatch(html, /Patient profile|Loading patient profile/)
      }
    })

    await context.test('the view shows the profile and marks missing data', () => {
      const html = renderProfile({
        id: 'syn-profile-41',
        identifiers: [{ system: 'http://electronichealth.se/identifier/personnummer', value: '199001012385' }],
        names: [{ use: 'official', text: null, family: 'Testsson', given: ['Tolvan'] }],
        gender: 'female',
        birthDate: '1990-01-01',
      })
      for (const text of ['Tolvan Testsson', '19900101-2385', '1990-01-01', 'Female']) {
        assert.ok(html.includes(`<dd>${text}</dd>`), `missing ${text}`)
      }
      // Internal FHIR IDs are not shown.
      assert.ok(!html.includes('syn-profile-41'))
      assert.doesNotMatch(html, /Patient ID/)
      const sparse = renderProfile({ id: 'syn-empty-1', identifiers: [], names: [], gender: null, birthDate: null })
      assert.equal(sparse.split('<dd>Not recorded</dd>').length - 1, 4)
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
