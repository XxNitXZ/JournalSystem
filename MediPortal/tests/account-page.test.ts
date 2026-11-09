import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'
import { accountFirstName, accountFullName } from '../src/features/auth/accountName.ts'
import { personalGreeting, timeOfDayGreeting } from '../src/features/auth/greeting.ts'

test('greets by the local time of day', () => {
  assert.equal(timeOfDayGreeting(new Date(2026, 0, 5, 8)), 'Good morning')
  assert.equal(timeOfDayGreeting(new Date(2026, 0, 5, 13)), 'Good afternoon')
  assert.equal(timeOfDayGreeting(new Date(2026, 0, 5, 21)), 'Good evening')
  assert.equal(timeOfDayGreeting(new Date(2026, 0, 5, 2)), 'Good evening')
})

test('uses only stored names and never derives them from the e-mail address', () => {
  const base = { id: 1, email: 'anna.testsson@example.test', role: 'PATIENT' as const }
  const morning = new Date(2026, 0, 5, 8)
  assert.equal(accountFullName({ ...base, givenName: ' Anna ', familyName: 'Testsson' }), 'Anna Testsson')
  assert.equal(accountFirstName({ ...base, givenName: 'Anna', familyName: 'Testsson' }), 'Anna')
  assert.equal(personalGreeting({ ...base, givenName: 'Anna', familyName: 'Testsson' }, morning), 'Good morning, Anna')
  for (const user of [base, { ...base, givenName: null, familyName: null }, { ...base, givenName: '  ', familyName: 'Testsson' }]) {
    assert.equal(accountFullName(user), null)
    assert.equal(personalGreeting(user, morning), 'Good morning')
  }
  assert.equal(accountFullName({ ...base, givenName: 'Anna', familyName: null }), null)
  assert.equal(personalGreeting(null, morning), 'Good morning')
})

test('account page and dashboards use real account data and professional copy', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  try {
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const state = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {}, acceptSession: () => {} }
    const account = (role: string) => ({ ...state, user: { id: 42, email: 'jane.doe@example.test', role } })
    const technical = /FHIR|HL7|CSRF|session-based|role-based|HTTP|backend|UTF-8/i
    // Visible text only: SVG markup contains xmlns URLs.
    const visible = (html: string) => html.replace(/<[^>]*>/g, ' ')

    for (const role of ['PATIENT', 'DOCTOR', 'STAFF']) {
      await context.test(`${role} account page shows account details with one sign-out action`, () => {
        const html = renderRoute(account(role), '/login')
        assert.match(html, /<h1 id="login-title">Your account<\/h1>/)
        assert.match(html, /<dd>jane\.doe@example\.test<\/dd>/)
        assert.match(html, new RegExp(`<dd>${role[0]}${role.slice(1).toLowerCase()}</dd>`))
        // No name is stored for accounts, so none is shown or derived from the e-mail address.
        assert.match(html, /<dd>Not on file<\/dd>/)
        assert.doesNotMatch(html, /Jane|Doe/)
        assert.equal(html.split('Sign out').length - 1, 1, 'exactly one Sign out action (in the header)')
        assert.doesNotMatch(visible(html), technical)
        assert.ok(html.includes(`href="/${role.toLowerCase()}"`))
      })

      await context.test(`${role} dashboard greets the user and shows the e-mail address as secondary information`, () => {
        const html = renderRoute(account(role), `/${role.toLowerCase()}`)
        assert.match(html, /Good (morning|afternoon|evening)/)
        assert.match(html, /Signed in as <span class="welcome__email">jane\.doe@example\.test<\/span>/)
        assert.doesNotMatch(html, /Jane|Doe/)
        assert.doesNotMatch(visible(html), technical)
      })
    }

    const named = (role: string) => ({
      ...state, user: { id: 7, email: 'anna.t@example.test', role, givenName: 'Anna', familyName: 'Testsson' },
    })

    for (const role of ['PATIENT', 'DOCTOR', 'STAFF']) {
      await context.test(`${role} with stored names: name first, e-mail second`, () => {
        const accountPage = renderRoute(named(role), '/login')
        assert.match(accountPage, /<dd>Anna Testsson<\/dd>/)
        assert.match(accountPage, /<dt>First name<\/dt><dd>Anna<\/dd><dt>Last name<\/dt><dd>Testsson<\/dd>/)
        assert.match(accountPage, /<dd>anna\.t@example\.test<\/dd>/)
        assert.doesNotMatch(accountPage, /Not on file/)
        // Header: full name as the main line and the e-mail address as secondary information.
        assert.match(accountPage, /<span class="user-chip__email">Anna Testsson<\/span><span class="user-chip__role">anna\.t@example\.test<\/span>/)
        assert.match(accountPage, /aria-label="Your account: Anna Testsson, /)

        const dashboard = renderRoute(named(role), `/${role.toLowerCase()}`)
        assert.match(dashboard, /Good (morning|afternoon|evening), Anna</)
        assert.match(dashboard, /Signed in as <span class="welcome__email">anna\.t@example\.test<\/span>/)
      })
    }

    await context.test('a single stored name is never completed or invented', () => {
      const partial = { ...state, user: { id: 8, email: 'only.first@example.test', role: 'STAFF', givenName: 'Anna', familyName: null } }
      const html = renderRoute(partial, '/login')
      assert.match(html, /<dd>Not on file<\/dd>/)
      assert.match(html, /<span class="user-chip__email">only\.first@example\.test<\/span>/)
    })

    await context.test('the registration form asks for first and last name', () => {
      const html = renderRoute(state, '/register')
      assert.match(html, /<label for="register-given-name" class="field__label">First name<\/label>/)
      assert.match(html, /<label for="register-family-name" class="field__label">Last name<\/label>/)
      assert.match(html, /name="givenName"/)
      assert.match(html, /autoComplete="given-name"|autocomplete="given-name"/)
      assert.match(html, /name="familyName"/)
      // An optional phone number, after the e-mail address and before the password.
      assert.match(html, /<label for="register-phone" class="field__label">Phone number \(optional\)<\/label>/)
      const phone = html.match(/<input[^>]*id="register-phone"[^>]*>/)?.[0] ?? ''
      assert.match(phone, /type="tel"/)
      assert.match(phone, /name="phoneNumber"/)
      assert.doesNotMatch(phone, /required/)
      assert.ok(html.indexOf('name="email"') < html.indexOf('name="phoneNumber"'))
      assert.ok(html.indexOf('name="phoneNumber"') < html.indexOf('name="password"'))
    })

    await context.test('sign-in, registration and home pages avoid technical wording', () => {
      for (const path of ['/login', '/register', '/']) {
        assert.doesNotMatch(visible(renderRoute(state, path)), technical, path)
      }
    })

    await context.test('patient screens for doctors and staff avoid technical wording', () => {
      for (const path of ['/patients/new', '/patients/syn-profile-41', '/doctor']) {
        assert.doesNotMatch(visible(renderRoute(account('DOCTOR'), path)), technical, path)
      }
    })
  } finally {
    await server.close()
  }
})
