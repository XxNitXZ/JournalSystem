import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

// Fictional patients only.
const candidate = (id: string, overrides: Record<string, unknown> = {}) => ({
  id, name: 'Ali Khan', personnummer: '200412201971', gender: 'male', birthDate: '2004-12-20',
  registered: false, importable: true, journalMatchable: true, ...overrides,
})

test('import existing patient: search, results, import and permissions', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const importer = await server.ssrLoadModule('/src/features/patientmanagement/patientImport.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const { renderResults } = await server.ssrLoadModule('/tests/patient-import-render.tsx')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const state = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {} }
    const as = (role: string) => ({ ...state, user: { id: 7, email: 'user@example.test', role } })

    await context.test('Import Patient is offered to doctors and staff only, next to Register patient', () => {
      for (const role of ['DOCTOR', 'STAFF']) {
        const dashboard = renderRoute(as(role), `/${role.toLowerCase()}`)
        assert.ok(dashboard.includes('href="/patients/import"'), `${role} link`)
        assert.match(dashboard, /Import an existing patient/)
        assert.ok(dashboard.includes('href="/patients/new"'), 'Register patient stays available')
        const page = renderRoute(as(role), '/patients/import')
        assert.match(page, /<h1 id="import-patient-title">Import existing patient<\/h1>/)
        for (const mode of ['NAME', 'PERSONNUMMER', 'ID']) assert.ok(page.includes(`<option value="${mode}"`), mode)
        assert.ok(page.includes('name="value"'))
      }
      const patientHome = renderRoute(as('PATIENT'), '/patient')
      assert.ok(!patientHome.includes('href="/patients/import"'))
      const denied = renderRoute(as('PATIENT'), '/patients/import')
      assert.match(denied, /available to doctor or staff accounts only/)
      assert.doesNotMatch(denied, /import-patient-title/)
      assert.doesNotMatch(renderRoute(state, '/patients/import'), /import-patient-title/)
    })

    await context.test('results show readable details, separate same-name patients and known patients', () => {
      const html = renderResults({ status: 'loaded', truncated: false, patients: [
        candidate('ext-1'),
        candidate('ext-2', { personnummer: null, journalMatchable: false, birthDate: '1990-01-01' }),
        candidate('ext-3', { registered: true }),
        candidate('ext-4', { name: null, importable: false, journalMatchable: false }),
      ] })
      const cards = html.match(/<li class="import-card">.*?<\/li>/g) ?? []
      assert.equal(cards.length, 4)
      assert.match(cards[0], /Ali Khan/)
      assert.match(cards[0], /<dt>Personnummer<\/dt><dd>20041220-1971<\/dd>/)
      assert.match(cards[0], /<dt>Gender<\/dt><dd>Male<\/dd>/)
      assert.match(cards[0], /<dt>Born<\/dt><dd>2004-12-20<\/dd>/)
      assert.match(cards[0], /<button[^>]*>.*Import Patient<\/button>/)
      // Same name, no personnummer: a separate result that can be imported but not matched with an account.
      assert.match(cards[1], /No personnummer/)
      assert.match(cards[1], /cannot be matched with a patient account/)
      assert.match(cards[1], /Import Patient/)
      // Already in MediCore: no import, a link to the existing profile.
      assert.match(cards[2], /In MediCore/)
      assert.match(cards[2], /href="\/patients\/ext-3"/)
      assert.doesNotMatch(cards[2], /Import Patient/)
      assert.match(cards[3], /Name not recorded/)
      assert.match(cards[3], /Cannot be imported/)
      assert.doesNotMatch(cards[3], /<button/)
      // Record IDs are never shown as text.
      assert.doesNotMatch(html.replace(/<[^>]*>/g, ' '), /ext-\d/)
      assert.match(html, /4 patients found/)
    })

    await context.test('a valid personnummer stored as YYYYMMDD-NNNN is matchable; invalid and ambiguous ones warn', () => {
      const html = renderResults({ status: 'loaded', truncated: false, patients: [
        // MediCore returns the canonical 12 digits for a record stored as 20041220-1971 on the KTH server.
        candidate('ext-kth', { personnummerStatus: 'VALID' }),
        candidate('ext-bad', { personnummer: '20041320-1971', journalMatchable: false, personnummerStatus: 'INVALID' }),
        candidate('ext-two', { personnummer: null, journalMatchable: false, personnummerStatus: 'AMBIGUOUS' }),
        candidate('ext-none', { personnummer: null, journalMatchable: false, personnummerStatus: 'MISSING' }),
      ] })
      const cards = html.match(/<li class="import-card">.*?<\/li>/g) ?? []
      assert.match(cards[0], /<dd>20041220-1971<\/dd>/)
      assert.doesNotMatch(cards[0], /cannot be matched/)
      assert.match(cards[1], /<dd>20041320-1971<\/dd>/)
      assert.match(cards[1], /No valid personnummer: can be used clinically, but cannot be matched with a patient account\./)
      assert.match(cards[2], /<dd>Several recorded<\/dd>/)
      assert.match(cards[2], /Several different personnummer are recorded/)
      assert.match(cards[3], /<dd>No personnummer<\/dd>/)
      assert.match(cards[3], /No personnummer: can be used clinically/)
      // Identifier systems are never shown.
      assert.doesNotMatch(html, /electronichealth|identifier\/personnummer/)
      assert.equal(importer.personnummerNote(candidate('x')), null)
      assert.match(importer.personnummerNote(candidate('x', { journalMatchable: false })), /No valid personnummer/)
      assert.equal(importer.formatCandidatePersonnummer('200412201971', 'VALID'), '20041220-1971')
    })

    await context.test('loading, empty, error, importing and bounded paging states', () => {
      assert.equal(renderResults({ status: 'idle' }), '')
      assert.match(renderResults({ status: 'searching' }), /Searching the patient records server…/)
      assert.match(renderResults({ status: 'loaded', patients: [], truncated: false }), /No patients were found\./)
      assert.match(renderResults({ status: 'failed', message: 'The search could not be completed.' }),
        /role="alert"[^>]*>.*could not be completed/)

      const importing = renderResults({ status: 'loaded', truncated: false, patients: [candidate('ext-1'), candidate('ext-2')] },
        1, 'ext-1')
      assert.match(importing, /Importing…/)
      for (const button of importing.match(/<button[^>]*>/g) ?? []) assert.match(button, /disabled/)

      const many = Array.from({ length: 25 }, (_, index) => candidate(`ext-${index}`))
      const first = renderResults({ status: 'loaded', patients: many, truncated: true })
      assert.equal((first.match(/<li class="import-card">/g) ?? []).length, 10)
      assert.match(first, /25 patients found · showing 1–10/)
      assert.match(first, /Page 1 of 3/)
      assert.match(first, /Only the first 100 matches are shown/)
      const last = renderResults({ status: 'loaded', patients: many, truncated: false }, 3)
      assert.equal((last.match(/<li class="import-card">/g) ?? []).length, 5)
      assert.match(last, /showing 21–25/)
      assert.deepEqual(importer.pageOf([1, 2, 3], 9, 2), { items: [3], page: 2, pages: 2 })
      assert.deepEqual(importer.pageOf([], 1), { items: [], page: 1, pages: 1 })
    })

    await context.test('searches are validated like MediCore', () => {
      assert.deepEqual(importer.importSearchRequest('NAME', '  Ali Khan '), { ok: true, mode: 'NAME', value: 'Ali Khan' })
      assert.deepEqual(importer.importSearchRequest('PERSONNUMMER', '20041220-1971'),
        { ok: true, mode: 'PERSONNUMMER', value: '200412201971' })
      assert.deepEqual(importer.importSearchRequest('ID', ' ext-9 '), { ok: true, mode: 'ID', value: 'ext-9' })
      for (const [mode, value, message] of [
        ['NAME', 'a', /two letters/], ['NAME', 'Ali1', /two letters/], ['NAME', '', /two letters/],
        ['PERSONNUMMER', '12345', /valid personnummer/], ['PERSONNUMMER', '200402301234', /valid personnummer/],
        ['ID', 'bad_id', /valid record ID/], ['ID', 'Patient/1', /valid record ID/], ['OTHER', 'Ali', /Select how/],
      ] as const) {
        const checked = importer.importSearchRequest(mode, value)
        assert.equal(checked.ok, false, `${mode} ${value}`)
        assert.match(checked.error, message)
      }
    })

    await context.test('the API calls send the search in the body and import by record ID with CSRF', async () => {
      const calls: { method: string; path: string; headers: Record<string, string>; body?: string }[] = []
      globalThis.fetch = async (url, options) => {
        calls.push({ method: String(options?.method), path: String(url).slice(API_BASE_URL.length),
          headers: options?.headers as Record<string, string>, body: options?.body as string | undefined })
        if (String(url).endsWith('/api/auth/csrf')) {
          return new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
        }
        return new Response(JSON.stringify(String(url).endsWith('/search')
          ? { patients: [candidate('ext-1')], truncated: false } : candidate('ext-1', { registered: true })),
        { status: String(url).endsWith('/search') ? 200 : 201 })
      }
      const found = await importer.discoverPatients('PERSONNUMMER', '200412201971')
      assert.equal(found.patients.length, 1)
      const imported = await importer.importPatient('ext-1')
      assert.equal(imported.registered, true)
      assert.deepEqual(calls.map((call) => `${call.method} ${call.path}`), [
        'GET /api/auth/csrf', 'POST /api/patients/import/search', 'GET /api/auth/csrf', 'POST /api/patients/import'])
      assert.deepEqual(JSON.parse(calls[1].body ?? ''), { mode: 'PERSONNUMMER', value: '200412201971' })
      assert.deepEqual(JSON.parse(calls[3].body ?? ''), { fhirPatientId: 'ext-1' })
      assert.equal(calls[3].headers['X-XSRF-TOKEN'], 'csrf-token')
      // The search value never appears in a URL.
      assert.ok(calls.every((call) => !call.path.includes('200412201971')))
    })

    await context.test('duplicates and failures are explained without technical details', () => {
      const message = (status: number, code?: string) =>
        importer.importErrorMessage(new ApiError(status, 'x', '/api/patients/import', code))
      assert.equal(message(409, 'ALREADY_REGISTERED'), 'This patient is already in MediCore.')
      assert.match(message(422, 'PATIENT_NOT_IMPORTABLE'), /cannot be imported/)
      assert.match(message(404), /no longer exists/)
      assert.match(message(403), /Only doctor and staff/)
      assert.match(message(503), /temporarily unavailable/)
      assert.match(importer.importErrorMessage(new TypeError('Failed to fetch')), /Unable to reach MediCore/)
      const search = (status: number) => importer.importSearchErrorMessage(new ApiError(status, 'x', '/api/patients/import/search'))
      assert.match(search(400), /Check the search/)
      assert.match(search(502), /temporarily unavailable/)
      assert.equal(importer.formatCandidatePersonnummer('20041220-1971'), '20041220-1971')
      assert.equal(importer.formatCandidateGender(null), 'Not recorded')
    })

    await context.test('patient registration and the profile route keep working', () => {
      for (const role of ['DOCTOR', 'STAFF']) {
        assert.match(renderRoute(as(role), '/patients/new'), /<h1 id="patient-registration-title">Register patient<\/h1>/)
        assert.match(renderRoute(as(role), '/patients/ext-1'), /Loading patient profile/)
      }
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
