import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('shared authentication drives patient navigation and routes', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  try {
    const { renderRoute, renderInitialSession } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const state = {
      user: null,
      loading: false,
      error: '',
      signIn: async () => {},
      signOut: async () => {},
    }
    const patient = { id: 42, email: 'patient@example.com', role: 'PATIENT' }

    await context.test('waits for session restoration before displaying patient content', () => {
      const html = renderInitialSession()
      assert.match(html, /Checking your session/)
      assert.doesNotMatch(html, /href="\/patient"/)
      assert.doesNotMatch(html, /Patient information and messaging/)
    })

    await context.test('patients see the navigation link and placeholder', () => {
      const html = renderRoute({ ...state, user: patient })
      assert.match(html, /href="\/patient"/)
      assert.match(html, /patient@example.com/)
      assert.match(html, /Patient information and messaging will be implemented later/)
    })

    await context.test('anonymous users see a sign-in link without patient navigation', () => {
      const html = renderRoute(state)
      assert.match(html, /sign in<\/a> to access the patient page/)
      assert.doesNotMatch(html, /href="\/patient"/)
      assert.doesNotMatch(html, /Patient information and messaging/)
    })

    await context.test('doctors and staff cannot display the patient landing page', () => {
      for (const role of ['DOCTOR', 'STAFF']) {
        const html = renderRoute({ ...state, user: { ...patient, role } })
        assert.match(html, /available to patient accounts only/)
        assert.doesNotMatch(html, /href="\/patient"/)
        assert.doesNotMatch(html, /Patient information and messaging/)
      }
    })

    await context.test('session failures show feedback without exposing patient content', () => {
      const html = renderRoute({ ...state, error: 'Unable to check your session.' })
      assert.match(html, /role="alert"/)
      assert.match(html, /Unable to check your session/)
      assert.doesNotMatch(html, /Patient information and messaging/)
    })

    await context.test('login and logout state is shared by account view and navigation', () => {
      const signedIn = renderRoute({ ...state, user: patient }, '/login')
      assert.match(signedIn, /href="\/patient"/)
      assert.match(signedIn, /patient@example.com/)
      assert.match(signedIn, /Role: PATIENT/)
      assert.match(signedIn, /Sign out/)
      const signedOut = renderRoute(state, '/login')
      assert.doesNotMatch(signedOut, /href="\/patient"/)
      assert.match(signedOut, /Login details/)
      assert.doesNotMatch(signedOut, /patient@example.com/)
    })
  } finally {
    await server.close()
  }
})
