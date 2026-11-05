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

    await context.test('anonymous users can navigate to registration and render its form', () => {
      const html = renderRoute(state, '/register')
      assert.match(html, /href="\/register"/)
      assert.match(html, /Registration details/)
      assert.match(html, /Create an account/)
    })

    await context.test('authenticated users cannot see Register or render its form', () => {
      for (const role of ['PATIENT', 'DOCTOR', 'STAFF']) {
        const html = renderRoute({ ...state, user: { ...patient, role } }, '/register')
        assert.doesNotMatch(html, /href="\/register"/)
        assert.doesNotMatch(html, /Registration details|Create an account/)
        assert.ok(html.includes(`href="/${role.toLowerCase()}"`))
      }
    })

    await context.test('registration waits for session restoration and blocks session errors', () => {
      for (const user of [null, patient]) {
        const loading = renderRoute({ ...state, user, loading: true }, '/register')
        assert.match(loading, /Checking your session/)
        assert.doesNotMatch(loading, /href="\/register"|Registration details/)
        const failed = renderRoute({ ...state, user, error: 'Session unavailable.' }, '/register')
        assert.match(failed, /role="alert"/)
        assert.match(failed, /Session unavailable/)
        assert.doesNotMatch(failed, /href="\/register"|Registration details/)
      }
    })

    for (const role of ['PATIENT', 'DOCTOR', 'STAFF']) {
      const page = role.toLowerCase()
      const title = page[0].toUpperCase() + page.slice(1) + ' home'
      await context.test(`${role} sees only its own navigation and landing content`, () => {
        const html = renderRoute({ ...state, user: { ...patient, role } }, `/${page}`)
        assert.ok(html.includes(`href="/${page}"`))
        assert.ok(html.includes(`>${title}</h1>`))
        assert.match(html, /patient@example.com/)
        assert.match(html, /will be implemented later/)
        for (const other of ['patient', 'doctor', 'staff'].filter((value) => value !== page)) {
          assert.ok(!html.includes(`href="/${other}"`))
          const denied = renderRoute({ ...state, user: { ...patient, role } }, `/${other}`)
          assert.ok(denied.includes(`available to ${other} accounts only`))
          assert.doesNotMatch(denied, /will be implemented later/)
          assert.doesNotMatch(denied, /Welcome,/)
        }
      })
    }

    for (const page of ['patient', 'doctor', 'staff']) {
      await context.test(`${page} handles loading, anonymous, and session error states`, () => {
        const user = { ...patient, role: page.toUpperCase() }
        const loading = renderRoute({ ...state, user, loading: true }, `/${page}`)
        assert.match(loading, /Checking your session/)
        assert.ok(!loading.includes(`href="/${page}"`))
        assert.doesNotMatch(loading, /will be implemented later/)
        const anonymous = renderRoute(state, `/${page}`)
        // Navigate redirects after mounting; server rendering must already hide the child.
        assert.doesNotMatch(anonymous, /Welcome,|accounts only|Checking your session/)
        assert.ok(!anonymous.includes(`href="/${page}"`))
        assert.doesNotMatch(anonymous, /will be implemented later/)
        const failed = renderRoute({ ...state, user, error: 'Session unavailable.' }, `/${page}`)
        assert.match(failed, /role="alert"/)
        assert.match(failed, /Session unavailable/)
        assert.ok(!failed.includes(`href="/${page}"`))
        assert.doesNotMatch(failed, /will be implemented later/)
      })
    }

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

    await context.test('anonymous users cannot render patient content before redirecting', () => {
      const html = renderRoute(state)
      assert.doesNotMatch(html, /Welcome,|accounts only/)
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
