import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

// Fictional data only.
const notes = [
  { id: 'note-2', patientId: 'syn-patient-1', encounter: { id: 'enc-2', start: '2026-11-10T14:30:00+01:00', type: 'Consultation' },
    author: 'Dr Ali Khan', createdAt: '2026-11-11T09:00:00+00:00', status: 'current', type: 'Clinical note',
    content: 'Patienten mår bättre.\nÅterbesök om två veckor.\n<script>alert(1)</script> **fet**', contentAvailable: true },
  { id: 'note-1', patientId: 'syn-patient-1', encounter: null, author: null, createdAt: null, status: 'superseded',
    type: null, content: null, contentAvailable: false },
]
const visits = [
  { id: 'enc-2', start: '2026-11-10T14:30:00+01:00', end: null, status: 'finished', type: 'Consultation',
    practitioners: ['Dr Ali Khan'], organization: null, location: null },
  { id: 'enc-1', start: '2026-11-05', end: null, status: 'finished', type: null, practitioners: [],
    organization: null, location: null },
]

test('clinical notes: list, add form, validation, API and permissions', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const form = await server.ssrLoadModule('/src/features/medicalrecord/clinicalNoteForm.ts')
    const service = await server.ssrLoadModule('/src/features/medicalrecord/clinicalNoteService.ts')
    const encounterForm = await server.ssrLoadModule('/src/features/medicalrecord/encounterForm.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const render = await server.ssrLoadModule('/tests/clinical-note-render.tsx')
    const encounterRender = await server.ssrLoadModule('/tests/encounter-render.tsx')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const { canEditPatients } = await server.ssrLoadModule('/src/features/patientmanagement/patientUpdate.ts')

    await context.test('the section lists date, author, encounter and text, newest first, never identifiers', () => {
      const html = render.renderNoteSection({ status: 'loaded', notes })
      assert.match(html, /<h2 id="clinical-notes-title">Clinical Notes<\/h2>/)
      const cards = html.match(/<li class="note-card">[\s\S]*?<\/li>/g) ?? []
      assert.equal(cards.length, 2)
      assert.match(cards[0], new RegExp(`<span class="note-card__date">${form.formatNoteDate(notes[0].createdAt)}</span>`))
      assert.match(cards[0], /Dr Ali Khan/)
      assert.match(cards[0], new RegExp(`Encounter: ${encounterForm.formatVisitDate(visits[0].start)} · Consultation`))
      assert.match(cards[1], /Date not recorded/)
      assert.match(cards[1], /Author not recorded/)
      assert.match(cards[1], /Encounter details not available/)
      assert.match(cards[1], /badge--warning">Superseded/)
      assert.match(cards[1], /stored in a format that MediCore cannot display/)
      assert.doesNotMatch(html, /note-1|note-2|enc-1|enc-2|syn-patient-1|Patient\/|Practitioner\//)
    })

    await context.test('note text is shown as plain text with its line breaks, never as HTML or Markdown', () => {
      const html = render.renderNoteList({ status: 'loaded', notes: [notes[0]] })
      assert.match(html, /<p class="note-card__text">Patienten mår bättre\.\nÅterbesök om två veckor\.\n&lt;script&gt;alert\(1\)&lt;\/script&gt; \*\*fet\*\*<\/p>/)
      assert.doesNotMatch(html, /<script>|<strong>|<b>/)
    })

    await context.test('loading, empty and error states', () => {
      assert.match(render.renderNoteList({ status: 'loading' }), /Loading clinical notes…/)
      assert.match(render.renderNoteList({ status: 'loaded', notes: [] }), /No clinical notes have been written yet\./)
      assert.match(render.renderNoteList({ status: 'failed', message: 'The clinical notes could not be loaded.' }),
        /role="alert"[^>]*>.*could not be loaded/)
      assert.match(render.renderNoteSection({ status: 'loading' }, 'syn-patient-1'), /Loading clinical notes…/)
    })

    await context.test('Add Clinical Note is offered to doctors and staff only', () => {
      for (const role of ['DOCTOR', 'STAFF']) {
        assert.equal(canEditPatients(role), true)
        const html = render.renderNoteSection({ status: 'loaded', notes }, canEditPatients(role) ? 'syn-patient-1' : undefined)
        assert.match(html, /<button[^>]*>.*Add Clinical Note<\/button>/)
        assert.match(html, /<button[^>]*>.*Refresh<\/button>/)
      }
      assert.equal(canEditPatients('PATIENT'), false)
      const patient = render.renderNoteSection({ status: 'loaded', notes })
      assert.doesNotMatch(patient, /Add Clinical Note|Refresh|<form|<textarea/)
      assert.match(patient, /Patienten mår bättre/)
    })

    await context.test('the form selects one of the patient\'s encounters by date and type, no technical IDs', () => {
      const html = render.renderNoteSection({ status: 'loaded', notes: [] }, 'syn-patient-1', true, visits)
      assert.match(html, /<h3 id="create-note-title">Add Clinical Note<\/h3>/)
      assert.match(html, /<option value="" disabled="" selected="">Select an encounter<\/option>/)
      assert.match(html, new RegExp(`<option value="enc-2">${encounterForm.formatVisitDate(visits[0].start)} · Consultation</option>`))
      assert.match(html, /<option value="enc-1">2026-11-05 · Visit type not recorded<\/option>/)
      assert.match(html, /<textarea id="note-content" name="content"/)
      assert.match(html, /0 \/ 10,000 characters/)
      assert.match(html, /Save note/)
      assert.match(html, /Cancel/)
      for (const forbidden of ['patientId', 'practitionerId', 'author', 'subject']) {
        assert.ok(!html.includes(`name="${forbidden}"`), forbidden)
      }
      assert.doesNotMatch(html.replace(/<[^>]*>/g, ' '), /syn-patient-1|enc-1|enc-2/)
      // The button is not shown twice while the form is open.
      assert.doesNotMatch(html, /Add Clinical Note<\/button>/)

      const selected = render.renderNoteForm({ status: 'loaded', complete: true, excludedCount: 0, encounters: visits }, 'enc-2', 'Hej då 🙂')
      assert.match(selected, /<option value="enc-2" selected="">/)
      assert.match(selected, new RegExp(`Selected visit: ${encounterForm.formatVisitDate(visits[0].start)} · Consultation`))
      // "Hej då 🙂" is 8 characters: the emoji counts once.
      assert.match(selected, /8 \/ 10,000 characters/)
    })

    await context.test('the form explains missing encounters and shows loading, saving and error states', () => {
      const none = render.renderNoteForm({ status: 'loaded', complete: true, excludedCount: 0, encounters: [] })
      assert.match(none, /no encounters yet/)
      assert.match(none, /Create Encounter first/)
      assert.match(none, /<fieldset disabled="">/)
      assert.match(render.renderNoteForm({ status: 'loading' }), /Loading encounters…/)
      assert.match(render.renderNoteForm({ status: 'failed', message: 'The encounters could not be loaded.' }),
        /role="alert"[^>]*>.*could not be loaded/)
      const saving = render.renderNoteForm({ status: 'loaded', complete: true, excludedCount: 0, encounters: visits }, 'enc-2', 'x', true)
      assert.match(saving, /Saving note…/)
      assert.match(saving, /aria-busy="true"/)
      assert.match(saving, /<fieldset disabled="">/)
      const failed = render.renderNoteForm({ status: 'loaded', complete: true, excludedCount: 0, encounters: visits }, 'enc-2', 'x', false,
        'The note could not be saved. Please try again later.')
      assert.match(failed, /role="alert".*The note could not be saved/)
      assert.match(failed, />x<\/textarea>/, 'the text is kept after a failure')
    })

    await context.test('encounter and note text are required and limited like in MediCore', () => {
      assert.deepEqual(form.clinicalNoteRequest({ encounterId: 'enc-2', content: '  Rad 1\r\nRad 2\n\n' }),
        { ok: true, request: { encounterId: 'enc-2', content: 'Rad 1\nRad 2' } })
      assert.equal(form.clinicalNoteRequest({ encounterId: 'enc-2', content: 'å'.repeat(10_000) }).ok, true)
      assert.equal(form.clinicalNoteRequest({ encounterId: 'enc-2', content: '🙂'.repeat(10_000) }).ok, true)
      assert.equal(form.noteLength(' 🙂🙂 \n'), 2)
      for (const [fields, message] of [
        [{ encounterId: '', content: 'Text' }, /Select the encounter/],
        [{ encounterId: 'enc-2', content: '' }, /Enter the note text/],
        [{ encounterId: 'enc-2', content: ' \n\t \r\n' }, /Enter the note text/],
        [{ encounterId: 'enc-2', content: 'å'.repeat(10_001) }, /at most 10,000 characters/],
        [{ encounterId: 'enc-2', content: 'a\u0000b' }, /cannot be saved/],
      ] as const) {
        const checked = form.clinicalNoteRequest(fields)
        assert.equal(checked.ok, false, JSON.stringify(fields).slice(0, 60))
        assert.match(checked.error, message)
      }
    })

    await context.test('a successful save notifies the section, which reloads the notes; failures and invalid input do not', async () => {
      const created: unknown[] = []
      const sent: unknown[] = []
      const ok = await form.submitClinicalNote({ encounterId: 'enc-2', content: ' Hej \n' }, {
        create: async (request: unknown) => { sent.push(request); return notes[0] },
        onCreated: (note: unknown) => created.push(note),
      })
      assert.deepEqual(ok, { ok: true, created: notes[0] })
      assert.deepEqual(created, [notes[0]])
      assert.deepEqual(sent, [{ encounterId: 'enc-2', content: 'Hej' }])

      const failed = await form.submitClinicalNote({ encounterId: 'enc-2', content: 'Hej' }, {
        create: async () => { throw new ApiError(422, 'x', '/api/patients/p/notes', 'ENCOUNTER_NOT_FOR_PATIENT') },
        onCreated: () => assert.fail('must not reload'),
      })
      assert.deepEqual(failed, { ok: false,
        error: 'The selected encounter does not belong to this patient. Reload the page and choose again.' })

      let requests = 0
      const invalid = await form.submitClinicalNote({ encounterId: 'enc-2', content: '   ' }, {
        create: async () => { requests += 1; return notes[0] },
        onCreated: () => assert.fail('must not reload'),
      })
      assert.equal(invalid.ok, false)
      assert.equal(requests, 0)
    })

    await context.test('a second submit while saving is ignored', async () => {
      const guard = form.createSubmitGuard()
      let release: () => void = () => {}
      let runs = 0
      const first = guard(() => new Promise<string>((resolve) => { runs += 1; release = () => resolve('saved') }))
      const second = await guard(async () => { runs += 1; return 'duplicate' })
      assert.equal(second, null)
      release()
      assert.equal(await first, 'saved')
      assert.equal(runs, 1)
      // After the first save has finished, a new save is possible again.
      assert.equal(await guard(async () => 'again'), 'again')
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
        return new Response(JSON.stringify(options?.method === 'POST' ? notes[0] : notes),
          { status: options?.method === 'POST' ? 201 : 200 })
      }
      assert.deepEqual(await service.listPatientNotes('syn/p 1'), notes)
      assert.deepEqual(await service.listOwnNotes(), notes)
      const request = { encounterId: 'enc-2', content: 'Mår bättre.\nÅterbesök.' }
      assert.deepEqual(await service.createClinicalNote('syn-patient-1', request), notes[0])
      assert.deepEqual(calls.map((call) => `${call.method} ${call.url.slice(API_BASE_URL.length)}`), [
        'GET /api/patients/syn%2Fp%201/notes',
        'GET /api/patients/me/notes',
        'GET /api/auth/csrf',
        'POST /api/patients/syn-patient-1/notes',
      ])
      assert.equal(calls[3].headers['X-XSRF-TOKEN'], 'csrf-token')
      assert.deepEqual(JSON.parse(calls[3].body ?? ''), request)
    })

    await context.test('errors are explained without technical details; an unconfirmed save is not called failed', () => {
      const message = (status: number, code?: string, action = 'create') =>
        form.clinicalNoteErrorMessage(new ApiError(status, 'x', '/api/patients/p/notes', code), action)
      assert.match(message(422, 'PRACTITIONER_NOT_LINKED'), /not linked to a practitioner/)
      assert.match(message(422, 'ENCOUNTER_NOT_FOUND'), /no longer exists/)
      assert.match(message(400, 'CONTENT_TOO_LONG'), /at most 10,000/)
      assert.match(message(404, 'RECORD_NOT_LINKED', 'load'), /do not have access to a medical record/)
      assert.match(message(403), /Only doctor and staff/)
      assert.match(message(403, undefined, 'load'), /do not have access to these clinical notes/)
      assert.match(message(404), /could not be found/)
      for (const status of [502, 503, 504]) {
        assert.match(message(status), /may or may not have been saved\. Refresh the notes before trying again/)
      }
      assert.match(message(503, undefined, 'load'), /temporarily unavailable/)
      assert.match(message(500, undefined, 'load'), /could not be loaded/)
      assert.match(form.clinicalNoteErrorMessage(new TypeError('Failed to fetch'), 'create'), /may not have been saved/)
      assert.equal(form.noteStatusLabel('current'), null)
      assert.equal(form.noteStatusLabel('entered-in-error'), 'Entered in error')
    })

    await context.test('the patient\'s own record shows notes without any write action; other pages keep working', () => {
      const state = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {} }
      const as = (role: string) => ({ ...state, user: { id: 7, email: 'user@example.test', role } })
      const record = renderRoute(as('PATIENT'), '/patient/record')
      assert.match(record, /<h1 id="my-record-title">My Medical Record<\/h1>/)
      assert.match(record, /diagnoses and clinical notes/)
      assert.doesNotMatch(record, /Add Clinical Note/)
      const own = render.renderNoteSection({ status: 'loaded', notes: [notes[0]] }, undefined, false)
      assert.match(own, /Patienten mår bättre/)
      assert.doesNotMatch(own, /Add Clinical Note/)
      for (const role of ['DOCTOR', 'STAFF']) {
        assert.match(renderRoute(as(role), '/patients/syn-patient-1'), /Loading patient profile/)
        assert.match(renderRoute(as(role), '/patient/record'), /available to patient accounts only/)
      }
      assert.match(renderRoute(as('PATIENT'), '/patients/syn-patient-1'), /available to doctor or staff accounts only/)
      // The Encounters section is unchanged.
      assert.match(encounterRender.renderSection({ status: 'loaded', complete: true, excludedCount: 0, encounters: visits }, 'syn-patient-1'),
        /<button[^>]*>.*Create Encounter<\/button>/)
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
