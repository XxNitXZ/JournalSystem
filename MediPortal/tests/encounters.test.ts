import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

// Fictional data only.
const visits = [
  { id: 'enc-2', start: '2026-11-10T14:30:00+01:00', end: null, status: 'finished', type: 'Consultation',
    practitioners: ['Dr Ali Khan'], organization: 'Vårdcentral Fiktiv', location: 'Mottagning 3' },
  { id: 'enc-1', start: '2026-11-05', end: null, status: 'planned', type: null, practitioners: [],
    organization: null, location: null },
]
const practitioners = [
  { id: 'p-own', name: 'Dr Ali Khan', own: true },
  { id: 'p-other', name: 'Dr Sara Ahmed', own: false },
]
const pad = (value: number) => String(value).padStart(2, '0')

test('encounters: list, create form, validation, API and permissions', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const form = await server.ssrLoadModule('/src/features/medicalrecord/encounterForm.ts')
    const service = await server.ssrLoadModule('/src/features/medicalrecord/encounterService.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const render = await server.ssrLoadModule('/tests/encounter-render.tsx')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const { canEditPatients } = await server.ssrLoadModule('/src/features/patientmanagement/patientUpdate.ts')

    await context.test('the list shows date, practitioner, type, status and place, never identifiers', () => {
      const html = render.renderList({ status: 'loaded', encounters: visits })
      const rows = html.match(/<tr>.*?<\/tr>/g) ?? []
      assert.equal(rows.length, 3, 'header and two visits')
      // Newest first, as delivered by MediCore; the time in local time.
      assert.match(rows[1], new RegExp(`<td class="data-table__date">${form.formatVisitDate(visits[0].start)}</td>`))
      assert.match(rows[1], /Dr Ali Khan/)
      assert.match(rows[1], /Consultation/)
      assert.match(rows[1], /badge badge--success">Finished/)
      assert.match(rows[1], /Mottagning 3, Vårdcentral Fiktiv/)
      assert.match(rows[2], /<td class="data-table__date">2026-11-05<\/td>/)
      assert.match(rows[2], /Not recorded/)
      assert.match(rows[2], /<span class="badge">Planned/)
      assert.doesNotMatch(html, /enc-1|enc-2|Patient\/|Practitioner\//)
    })

    await context.test('loading, empty and error states', () => {
      assert.match(render.renderList({ status: 'loading' }), /Loading encounters…/)
      assert.match(render.renderList({ status: 'loaded', encounters: [] }), /No encounters have been recorded yet\./)
      assert.match(render.renderList({ status: 'loaded', encounters: [] }, 'No visits have been recorded yet.'),
        /No visits have been recorded yet\./)
      assert.match(render.renderList({ status: 'failed', message: 'The encounters could not be loaded.' }),
        /role="alert"[^>]*>.*could not be loaded/)
    })

    await context.test('Create Encounter is offered to doctors and staff only', () => {
      assert.equal(canEditPatients('DOCTOR'), true)
      assert.equal(canEditPatients('STAFF'), true)
      assert.equal(canEditPatients('PATIENT'), false)
      const clinician = render.renderSection({ status: 'loaded', encounters: visits }, 'syn-patient-1')
      assert.match(clinician, /<h2 id="encounters-title">Encounters<\/h2>/)
      assert.match(clinician, /<button[^>]*>.*Create Encounter<\/button>/)
      // The patient's own view: the same list, no create action.
      const patient = render.renderSection({ status: 'loaded', encounters: visits })
      assert.doesNotMatch(patient, /Create Encounter/)
      assert.match(patient, /Dr Ali Khan/)
    })

    await context.test('the form uses the open patient, practitioners by name and the own one preselected', () => {
      const html = render.renderSection({ status: 'loaded', encounters: [] }, 'syn-patient-1', true, practitioners)
      assert.match(html, /<h3 id="create-encounter-title">Create Encounter<\/h3>/)
      assert.match(html, /<option value="p-own" selected="">Dr Ali Khan \(you\)<\/option>/)
      assert.match(html, /<option value="p-other">Dr Sara Ahmed<\/option>/)
      assert.match(html, /type="datetime-local"/)
      assert.match(html, /<option value="CONSULTATION" selected="">Consultation<\/option>/)
      assert.match(html, /<option value="FINISHED" selected="">Finished<\/option>/)
      // No patient field and no technical IDs to type in.
      for (const forbidden of ['patientId', 'subject', 'organizationId', 'locationId']) {
        assert.ok(!html.includes(`name="${forbidden}"`), forbidden)
      }
      assert.doesNotMatch(html, /syn-patient-1/)
      // While the form is open, the button is not shown twice.
      assert.equal(html.split('Create Encounter</button>').length - 1, 1)
    })

    await context.test('the form shows loading, unavailable practitioners and saving states', () => {
      const loading = render.renderForm({ status: 'loading' })
      assert.match(loading, /Loading practitioners…/)
      assert.match(loading, /<fieldset disabled="">/)
      const none = render.renderForm({ status: 'loaded', practitioners: [] })
      assert.match(none, /No practitioners are available yet/)
      assert.match(none, /<fieldset disabled="">/)
      const saving = render.renderForm({ status: 'loaded', practitioners }, true)
      assert.match(saving, /Creating encounter…/)
      assert.match(saving, /aria-busy="true"/)
      const failed = render.renderForm({ status: 'loaded', practitioners }, false, 'The encounter could not be created.')
      assert.match(failed, /role="alert"[^>]*>.*could not be created/)
    })

    await context.test('dates are validated and sent with the local offset', () => {
      const now = new Date(2026, 10, 11, 12, 0)
      const valid = { visitTime: '2026-11-10T14:30', practitionerId: 'p-own', type: 'FOLLOW_UP', status: 'FINISHED' }
      const result = form.encounterRequest(valid, now)
      assert.equal(result.ok, true)
      const expectedDate = new Date(2026, 10, 10, 14, 30)
      const offset = -expectedDate.getTimezoneOffset()
      const expectedOffset = `${offset >= 0 ? '+' : '-'}${pad(Math.floor(Math.abs(offset) / 60))}:${pad(Math.abs(offset) % 60)}`
      assert.deepEqual(result.request, { startTime: `2026-11-10T14:30:00${expectedOffset}`, practitionerId: 'p-own',
        type: 'FOLLOW_UP', status: 'FINISHED' })
      assert.deepEqual(Object.keys(result.request).sort(), ['practitionerId', 'startTime', 'status', 'type'])
      // The ISO value describes the same moment as the local input.
      assert.equal(new Date(result.request.startTime).getTime(), expectedDate.getTime())

      for (const [fields, message] of [
        [{ ...valid, visitTime: '' }, /valid date and time/],
        [{ ...valid, visitTime: '2026-02-30T10:00' }, /valid date and time/],
        [{ ...valid, visitTime: '2026-11-10 14:30' }, /valid date and time/],
        [{ ...valid, visitTime: '1850-01-01T10:00' }, /valid date and time/],
        [{ ...valid, visitTime: '2026-11-12T10:00' }, /cannot be in the future/],
        [{ ...valid, visitTime: '2026-11-12T10:00', status: 'IN_PROGRESS' }, /cannot be in the future/],
        [{ ...valid, practitionerId: '' }, /Select a practitioner/],
        [{ ...valid, type: 'SURGERY' }, /Select a visit type/],
        [{ ...valid, status: 'ARRIVED' }, /Select a status/],
      ] as const) {
        const checked = form.encounterRequest(fields, now)
        assert.equal(checked.ok, false, JSON.stringify(fields))
        assert.match(checked.error, message)
      }
      // A planned visit may be in the future; five minutes of clock difference are accepted.
      assert.equal(form.encounterRequest({ ...valid, visitTime: '2027-01-15T08:00', status: 'PLANNED' }, now).ok, true)
      assert.equal(form.encounterRequest({ ...valid, visitTime: '2026-11-11T12:04' }, now).ok, true)
      assert.equal(form.toLocalInputValue(new Date(2026, 0, 5, 7, 3)), '2026-01-05T07:03')
    })

    await context.test('a successful create notifies the section, which reloads the list; failures do not', async () => {
      const valid = { visitTime: '2026-11-10T14:30', practitionerId: 'p-own', type: 'CONSULTATION', status: 'FINISHED' }
      const now = new Date(2026, 10, 11, 12, 0)
      const created: unknown[] = []
      const sent: unknown[] = []
      const ok = await form.submitEncounter(valid, {
        create: async (request: unknown) => { sent.push(request); return visits[0] },
        onCreated: (visit: unknown) => created.push(visit),
      }, now)
      assert.deepEqual(ok, { ok: true, created: visits[0] })
      assert.deepEqual(created, [visits[0]])
      assert.equal(sent.length, 1)

      const failed = await form.submitEncounter(valid, {
        create: async () => { throw new ApiError(400, 'x', '/api/patients/p/encounters', 'PRACTITIONER_NOT_AVAILABLE') },
        onCreated: () => assert.fail('must not reload'),
      }, now)
      assert.deepEqual(failed, { ok: false, error: 'The selected practitioner cannot be used. Choose another practitioner.' })

      let requests = 0
      const invalid = await form.submitEncounter({ ...valid, visitTime: '' }, {
        create: async () => { requests += 1; return visits[0] },
        onCreated: () => assert.fail('must not reload'),
      }, now)
      assert.equal(invalid.ok, false)
      assert.equal(requests, 0)
    })

    await context.test('the API calls use the patient in the URL, the session and CSRF', async () => {
      const calls: { method: string; url: string; headers: Record<string, string>; body?: string }[] = []
      globalThis.fetch = async (url, options) => {
        calls.push({ method: String(options?.method), url: String(url), headers: options?.headers as Record<string, string>,
          body: options?.body as string | undefined })
        assert.equal(options?.credentials, 'include')
        if (String(url).endsWith('/api/auth/csrf')) {
          return new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
        }
        if (String(url).endsWith('/api/practitioners')) return new Response(JSON.stringify(practitioners))
        return new Response(JSON.stringify(options?.method === 'POST' ? visits[0] : visits))
      }
      assert.deepEqual(await service.listPatientEncounters('syn/p 1'), visits)
      assert.deepEqual(await service.listOwnEncounters(), visits)
      assert.deepEqual(await service.listPractitioners(), practitioners)
      const request = { startTime: '2026-11-10T14:30:00+01:00', practitionerId: 'p-own', type: 'CONSULTATION',
        status: 'FINISHED' }
      assert.deepEqual(await service.createEncounter('syn-patient-1', request), visits[0])
      assert.deepEqual(calls.map((call) => `${call.method} ${call.url.slice(API_BASE_URL.length)}`), [
        'GET /api/patients/syn%2Fp%201/encounters',
        'GET /api/patients/me/encounters',
        'GET /api/practitioners',
        'GET /api/auth/csrf',
        'POST /api/patients/syn-patient-1/encounters',
      ])
      assert.equal(calls[4].headers['X-XSRF-TOKEN'], 'csrf-token')
      assert.deepEqual(JSON.parse(calls[4].body ?? ''), request)
    })

    await context.test('errors are explained without technical details', () => {
      const message = (status: number, code?: string, action = 'create') =>
        form.encounterErrorMessage(new ApiError(status, 'x', '/api/patients/p/encounters', code), action)
      assert.match(message(400, 'START_TIME_IN_FUTURE'), /Choose Planned/)
      assert.match(message(400, 'PRACTITIONER_NOT_AVAILABLE'), /cannot be used/)
      assert.match(message(422, 'PRACTITIONER_NOT_FOUND'), /no longer exists/)
      assert.match(message(404, 'RECORD_NOT_LINKED', 'load'), /do not have access to a medical record/)
      assert.match(message(403), /Only doctor and staff/)
      assert.match(message(404), /could not be found/)
      assert.match(message(503), /may not have been saved/)
      assert.match(message(503, undefined, 'load'), /temporarily unavailable/)
      assert.match(message(500, undefined, 'load'), /could not be loaded/)
      assert.match(form.encounterErrorMessage(new TypeError('Failed to fetch'), 'load'), /Unable to reach MediCore/)
      assert.equal(form.formatVisitStatus('in-progress'), 'In progress')
      assert.equal(form.formatVisitStatus('entered-in-error'), 'Entered in error')
      assert.equal(form.formatVisitDate(null), 'Date not recorded')
      assert.equal(form.formatPlace(null, null), null)
    })

    await context.test('existing routes and the patient record stay protected', () => {
      const state = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {} }
      const as = (role: string) => ({ ...state, user: { id: 7, email: 'user@example.test', role } })
      assert.match(renderRoute(as('PATIENT'), '/patient/record'), /<h1 id="my-record-title">My Medical Record<\/h1>/)
      for (const role of ['DOCTOR', 'STAFF']) {
        assert.match(renderRoute(as(role), '/patients/syn-patient-1'), /Loading patient profile/)
        assert.match(renderRoute(as(role), '/patient/record'), /available to patient accounts only/)
      }
      assert.match(renderRoute(as('PATIENT'), '/patients/syn-patient-1'), /available to doctor or staff accounts only/)
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
