import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'
import { validateSearchQuery } from '../src/features/patientmanagement/searchQuery.ts'

// Synthetic data only: personnummer from Skatteverket's published test personnummer, or deliberately invalid.
const SYSTEM = 'http://electronichealth.se/identifier/personnummer'
const patient = (id: string, given: string, family: string, personnummer: string, birthDate: string) => ({
  id, identifiers: [{ system: SYSTEM, value: personnummer }],
  names: [{ use: 'official', text: null, family, given: [given] }], gender: 'female', birthDate,
})

test('search queries follow the MediCore rules', () => {
  // A wrong Luhn check digit (199001012386, 200412201971) is still an exact personnummer search.
  for (const query of ['Anna', 'Testsson', 'Anna Testsson', ' anna-karin ', "O'Neil", 'Åke Öberg', '199001012385',
    '19900101-2385', '199001012386', '19900101-2386', '200412201971']) {
    assert.equal(validateSearchQuery(query), null, query)
  }
  for (const query of ['', ' ', 'a', 'Anna1', 'Anna <b>', 'a b c d e f', '199002301234', '19900230-1234',
    '199013019808', '19900101238', 'x'.repeat(101)]) {
    const message = validateSearchQuery(query)
    assert.ok(message, `accepted ${query.length} characters`)
    assert.doesNotMatch(message, /Anna|1990/)
  }
})

test('patient search API, results and error messages', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const { searchPatients } = await server.ssrLoadModule('/src/features/patientmanagement/patientService.ts')
    const { patientSearchErrorMessage } = await server.ssrLoadModule('/src/features/patientmanagement/patientSearch.ts')
    const { patientErrorMessage } = await server.ssrLoadModule('/src/features/patientmanagement/patientErrorMessage.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const { renderResults } = await server.ssrLoadModule('/tests/patient-search-render.tsx')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const twins = [patient('fhir-a1', 'Anna', 'Testsson', '199001012385', '1990-01-01'),
      patient('fhir-a2', 'Anna', 'Testsson', '198506152381', '1985-06-15')]

    await context.test('the query travels in a POST body with CSRF, never in the URL', async () => {
      const calls: string[] = []
      globalThis.fetch = async (url, options) => {
        calls.push(`${options?.method} ${String(url).slice(API_BASE_URL.length)}`)
        assert.equal(options?.credentials, 'include')
        if (String(url).endsWith('/api/auth/csrf')) return new Response(JSON.stringify({ token: 't', headerName: 'X-CSRF-TOKEN' }))
        assert.deepEqual(JSON.parse(String(options?.body)), { query: '199001012385' })
        return new Response(JSON.stringify(twins.slice(0, 1)))
      }
      assert.deepEqual(await searchPatients('199001012385'), twins.slice(0, 1))
      assert.deepEqual(calls, ['GET /api/auth/csrf', 'POST /api/patients/search'])
      assert.ok(calls.every((call) => !call.includes('199001012385')))
    })

    await context.test('error codes from the response body reach the messages', async () => {
      globalThis.fetch = async (url) => String(url).endsWith('/api/auth/csrf')
        ? new Response(JSON.stringify({ token: 't', headerName: 'X-CSRF-TOKEN' }))
        : new Response(JSON.stringify({ code: 'TOO_MANY_RESULTS' }), { status: 422 })
      await assert.rejects(searchPatients('Anna'), (error: unknown) =>
        error instanceof ApiError && error.status === 422 && error.code === 'TOO_MANY_RESULTS'
        && /refine your search/.test(patientSearchErrorMessage(error)))
      // Unexpected or empty bodies carry no code.
      globalThis.fetch = async (url) => String(url).endsWith('/api/auth/csrf')
        ? new Response(JSON.stringify({ token: 't', headerName: 'X-CSRF-TOKEN' }))
        : new Response('{"code":"<script>"}', { status: 422 })
      await assert.rejects(searchPatients('Anna'), (error: unknown) => error instanceof ApiError && error.code === undefined)
    })

    await context.test('search errors never repeat the query', () => {
      for (const [status, message] of [[400, /at least two letters/], [401, /Sign in again/], [403, /Only doctor and staff/],
        [503, /temporarily unavailable/], [500, /could not be completed/]] as const) {
        const text = patientSearchErrorMessage(new ApiError(status, 'x', '/api/patients/search'))
        assert.match(text, message)
      }
      assert.match(patientSearchErrorMessage(new TypeError('Failed to fetch')), /Unable to reach MediCore/)
    })

    await context.test('every match is listed separately with name, personnummer and an Open profile action', () => {
      const html = renderResults(twins)
      assert.equal(html.split('<li ').length - 1, 2)
      assert.equal(html.split('Anna Testsson').length - 1, 4) // visible name and aria-label, for each row
      assert.match(html, /<span class="search-results__personnummer">19900101-2385<\/span>/)
      assert.match(html, /<span class="search-results__personnummer">19850615-2381<\/span>/)
      assert.match(html, /href="\/patients\/fhir-a1"[^>]*>Open profile/)
      assert.match(html, /href="\/patients\/fhir-a2"[^>]*>Open profile/)
      assert.match(html, /aria-label="Open profile for Anna Testsson, 19850615-2381"/)
      // The internal IDs appear only in the link targets, never as visible text.
      const visible = html.replace(/<[^>]*>/g, ' ')
      assert.ok(!visible.includes('fhir-a1') && !visible.includes('fhir-a2'))
    })

    await context.test('the dashboards offer the search; patients do not get it', () => {
      const state = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {}, acceptSession: () => {} }
      for (const role of ['DOCTOR', 'STAFF']) {
        const html = renderRoute({ ...state, user: { id: 1, email: 'x@example.test', role } }, `/${role.toLowerCase()}`)
        assert.match(html, /<label for="patient-search-query" class="field__label">Name or personnummer<\/label>/)
        assert.match(html, /Only patients registered in\s+MediCore are shown/)
      }
      const patientHome = renderRoute({ ...state, user: { id: 2, email: 'p@example.test', role: 'PATIENT' } }, '/patient')
      assert.doesNotMatch(patientHome, /patient-search-query/)
    })

    await context.test('registration asks for fictional identities without an allowlist and reports registry errors', () => {
      const state = { user: { id: 1, email: 'x@example.test', role: 'STAFF' }, loading: false, error: '',
        signIn: async () => {}, signOut: async () => {}, acceptSession: () => {} }
      const html = renderRoute(state, '/patients/new')
      assert.match(html, /Use fictional identities only/)
      assert.match(html, /starting with the date of birth/)
      assert.doesNotMatch(html, /check digit/)
      // No mandatory list of official test numbers any more.
      assert.doesNotMatch(html, /Skatteverket|official test personnummer|test server/)
      // A 422 is the generic rejection by the records server, whatever its body.
      assert.match(patientErrorMessage(new ApiError(422, 'x', '/api/patients', 'SYNTHETIC_IDENTITY_REQUIRED')),
        /could not be saved/)
      assert.match(patientErrorMessage(new ApiError(500, 'x', '/api/patients', 'REGISTRY_FAILED')),
        /Do not register the patient again/)
      // A 422 without the code is still the generic FHIR rejection.
      assert.match(patientErrorMessage(new ApiError(422, 'x', '/api/patients')), /could not be saved/)
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
