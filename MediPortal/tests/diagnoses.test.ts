import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

// Fictional data only.
const diagnoses = [
  { id: 'cond-2', patientId: 'syn-patient-1', diagnosis: 'Typ 2-diabetes',
    code: { system: 'http://snomed.info/sct', systemName: 'SNOMED CT', code: '44054006', display: null },
    clinicalStatus: 'active', verificationStatus: 'confirmed', category: 'encounter-diagnosis',
    recordedDate: '2026-11-12T09:30:12Z', onset: '2026-10-01',
    encounter: { id: 'enc-2', start: '2026-11-10T14:30:00+01:00', type: 'Consultation' }, recorder: 'Dr Ali Khan' },
  // Recorded outside MediCore for an imported patient: coding only, no statuses or dates.
  { id: 'cond-1', patientId: 'syn-patient-1', diagnosis: 'Asthma',
    code: { system: 'http://snomed.info/sct', systemName: 'SNOMED CT', code: '195967001', display: 'Asthma' },
    clinicalStatus: null, verificationStatus: null, category: null, recordedDate: null, onset: 'Age 45 years',
    encounter: null, recorder: null },
  { id: 'cond-0', patientId: 'syn-patient-1', diagnosis: null,
    code: { system: 'urn:other', systemName: null, code: 'X1', display: null },
    clinicalStatus: 'resolved', verificationStatus: 'refuted', category: null, recordedDate: null, onset: null,
    encounter: null, recorder: null },
]
const visits = [
  { id: 'enc-2', start: '2026-11-10T14:30:00+01:00', end: null, status: 'finished', type: 'Consultation',
    practitioners: ['Dr Ali Khan'], organization: null, location: null },
]
const valid = { diagnosis: 'Typ 2-diabetes', code: '', codeSystem: '', clinicalStatus: 'ACTIVE',
  verificationStatus: 'CONFIRMED', onsetDate: '', encounterId: '' }
const now = new Date(2026, 10, 12, 12, 0)

test('diagnoses: list, add form, validation, API and permissions', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const form = await server.ssrLoadModule('/src/features/medicalrecord/diagnosisForm.ts')
    const service = await server.ssrLoadModule('/src/features/medicalrecord/diagnosisService.ts')
    const notesForm = await server.ssrLoadModule('/src/features/medicalrecord/clinicalNoteForm.ts')
    const encounterForm = await server.ssrLoadModule('/src/features/medicalrecord/encounterForm.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const render = await server.ssrLoadModule('/tests/diagnosis-render.tsx')
    const noteRender = await server.ssrLoadModule('/tests/clinical-note-render.tsx')
    const encounterRender = await server.ssrLoadModule('/tests/encounter-render.tsx')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const { canEditPatients } = await server.ssrLoadModule('/src/features/patientmanagement/patientUpdate.ts')

    await context.test('the section shows diagnosis, code, statuses, dates, encounter and recorder, never identifiers', () => {
      const html = render.renderDiagnosisSection({ status: 'loaded', diagnoses })
      assert.match(html, /<h2 id="diagnoses-title">Diagnoses<\/h2>/)
      const cards = html.match(/<li class="note-card">[\s\S]*?<\/li>/g) ?? []
      assert.equal(cards.length, 3)
      assert.match(cards[0], /<span class="diagnosis-card__name">Typ 2-diabetes<\/span>/)
      assert.match(cards[0], /badge--danger">Active/)
      assert.match(cards[0], /badge--primary">Confirmed/)
      assert.match(cards[0], /<dt>Code<\/dt><dd>SNOMED CT 44054006<\/dd>/)
      assert.match(cards[0], new RegExp(`<dt>Recorded</dt><dd>${encounterForm.formatVisitDate('2026-11-12T09:30:12Z')}</dd>`))
      assert.match(cards[0], /<dt>Onset<\/dt><dd>2026-10-01<\/dd>/)
      assert.match(cards[0], /<dt>Category<\/dt><dd>Encounter diagnosis<\/dd>/)
      assert.match(cards[0], /<dt>Recorded by<\/dt><dd>Dr Ali Khan<\/dd>/)
      assert.match(cards[0], new RegExp(`Encounter: ${encounterForm.formatVisitDate(visits[0].start)} · Consultation`))
      assert.doesNotMatch(html, /cond-\d|enc-2|syn-patient-1|Patient\/|Condition\//)
    })

    await context.test('imported diagnoses without statuses or dates are shown with what they have', () => {
      const html = render.renderDiagnosisList({ status: 'loaded', diagnoses: diagnoses.slice(1) })
      const cards = html.match(/<li class="note-card">[\s\S]*?<\/li>/g) ?? []
      assert.match(cards[0], /diagnosis-card__name">Asthma</)
      assert.match(cards[0], /<dt>Recorded<\/dt><dd>Not recorded<\/dd>/)
      assert.match(cards[0], /<dt>Onset<\/dt><dd>Age 45 years<\/dd>/)
      assert.doesNotMatch(cards[0], /badge|Encounter:|Recorded by/)
      // Only a code from another system: the code is the title; unknown statuses are shown readably.
      assert.match(cards[1], /diagnosis-card__name">X1</)
      assert.match(cards[1], /Resolved/)
      assert.match(cards[1], /Refuted/)
    })

    await context.test('loading, empty and error states', () => {
      assert.match(render.renderDiagnosisList({ status: 'loading' }), /Loading diagnoses…/)
      assert.match(render.renderDiagnosisList({ status: 'loaded', diagnoses: [] }), /No diagnoses have been recorded yet\./)
      assert.match(render.renderDiagnosisList({ status: 'failed', message: 'The diagnoses could not be loaded.' }),
        /role="alert".*could not be loaded/)
    })

    await context.test('Add Diagnosis is offered to doctors and staff only; patients only read', () => {
      for (const role of ['DOCTOR', 'STAFF']) {
        const html = render.renderDiagnosisSection({ status: 'loaded', diagnoses },
          canEditPatients(role) ? 'syn-patient-1' : undefined)
        assert.match(html, /<button[^>]*>.*Add Diagnosis<\/button>/)
        assert.match(html, /Refresh<\/button>/)
      }
      const patient = render.renderDiagnosisSection({ status: 'loaded', diagnoses },
        canEditPatients('PATIENT') ? 'syn-patient-1' : undefined)
      assert.doesNotMatch(patient, /Add Diagnosis|Refresh|Edit|Delete|<form|<input/)
      assert.match(patient, /Typ 2-diabetes/)
    })

    await context.test('the form has the diagnosis, optional code, statuses, onset and optional encounter, no IDs', () => {
      const html = render.renderDiagnosisSection({ status: 'loaded', diagnoses: [] }, 'syn-patient-1', true, visits)
      assert.match(html, /<h3 id="create-diagnosis-title">Add Diagnosis<\/h3>/)
      assert.match(html, /<input id="diagnosis-text"[^>]*name="diagnosis"/)
      assert.match(html, /<option value="" selected="">No code<\/option>/)
      assert.match(html, /<option value="SNOMED_CT">SNOMED CT<\/option>/)
      assert.match(html, /<option value="ICD_10">ICD-10<\/option>/)
      assert.match(html, /<input id="diagnosis-code"[^>]*disabled=""[^>]*name="code"/)
      assert.match(html, /<option value="ACTIVE" selected="">Active<\/option>/)
      assert.match(html, /<option value="CONFIRMED" selected="">Confirmed<\/option>/)
      assert.doesNotMatch(html, /value="REFUTED"|value="ENTERED_IN_ERROR"/)
      assert.match(html, /type="date"/)
      assert.match(html, /<option value="" selected="">No encounter<\/option>/)
      assert.match(html, new RegExp(`<option value="enc-2">${encounterForm.formatVisitDate(visits[0].start)} · Consultation</option>`))
      for (const forbidden of ['patientId', 'practitionerId', 'recorder', 'subject']) {
        assert.ok(!html.includes(`name="${forbidden}"`), forbidden)
      }
      assert.doesNotMatch(html.replace(/<[^>]*>/g, ' '), /syn-patient-1|enc-2/)
      assert.doesNotMatch(html, /Add Diagnosis<\/button>/)
      // Encounters are optional: if they cannot be loaded the diagnosis can still be recorded.
      const failed = render.renderDiagnosisForm({ status: 'failed', message: 'The encounters could not be loaded.' })
      assert.match(failed, /without an encounter/)
      assert.doesNotMatch(failed, /<fieldset disabled/)
    })

    await context.test('field errors are shown next to the field; saving disables the form', () => {
      const html = render.renderDiagnosisForm({ status: 'loaded', encounters: visits }, { codeSystem: 'SNOMED_CT', code: '123' },
        { field: 'code', message: 'This is not a valid SNOMED CT concept ID. Check the code or leave it empty.' })
      assert.match(html, /<input id="diagnosis-code"[^>]*aria-invalid="true"[^>]*aria-describedby="diagnosis-code-help diagnosis-code-error"/)
      assert.match(html, /<p id="diagnosis-code-error" class="field__error" role="alert">This is not a valid SNOMED CT/)
      const saving = render.renderDiagnosisForm({ status: 'loaded', encounters: visits }, { diagnosis: 'x' }, null, true)
      assert.match(saving, /Saving diagnosis…/)
      assert.match(saving, /aria-busy="true"/)
      assert.match(saving, /<fieldset disabled="">/)
      const failed = render.renderDiagnosisForm({ status: 'loaded', encounters: visits }, { diagnosis: 'Astma' }, null,
        false, 'The diagnosis could not be saved. Please try again later.')
      assert.match(failed, /role="alert".*The diagnosis could not be saved/)
      assert.match(failed, /value="Astma"/, 'the entered values are kept after a failure')
    })

    await context.test('diagnosis, code, statuses and onset are validated like in MediCore', () => {
      assert.deepEqual(form.diagnosisRequest({ ...valid, diagnosis: '  Typ 2-diabetes ' }, now),
        { ok: true, request: { diagnosis: 'Typ 2-diabetes', clinicalStatus: 'ACTIVE', verificationStatus: 'CONFIRMED' } })
      assert.deepEqual(form.diagnosisRequest({ ...valid, code: ' 44054006 ', codeSystem: 'SNOMED_CT',
        clinicalStatus: 'REMISSION', verificationStatus: 'PROVISIONAL', onsetDate: '2026-11-12', encounterId: 'enc-2' }, now),
      { ok: true, request: { diagnosis: 'Typ 2-diabetes', code: '44054006', codeSystem: 'SNOMED_CT',
        clinicalStatus: 'REMISSION', verificationStatus: 'PROVISIONAL', onsetDate: '2026-11-12', encounterId: 'enc-2' } })
      assert.equal(form.diagnosisRequest({ ...valid, code: 'e11.9', codeSystem: 'ICD_10' }, now).request.code, 'E11.9')
      for (const code of ['44054006', '38341003', '195967001']) assert.equal(form.normalizeDiagnosisCode('SNOMED_CT', code), code)
      for (const code of ['44054007', '12345', '044054006', '44054016']) assert.equal(form.normalizeDiagnosisCode('SNOMED_CT', code), null)
      for (const [fields, field, message] of [
        [{ ...valid, diagnosis: '  ' }, 'diagnosis', /Enter the diagnosis/],
        [{ ...valid, diagnosis: 'x'.repeat(251) }, 'diagnosis', /at most 250/],
        [{ ...valid, diagnosis: 'Rad 1\nRad 2' }, 'diagnosis', /single line/],
        [{ ...valid, code: 'J45' }, 'codeSystem', /Select the code system/],
        [{ ...valid, codeSystem: 'ICD_10' }, 'code', /Enter the diagnosis code/],
        [{ ...valid, code: '44054007', codeSystem: 'SNOMED_CT' }, 'code', /not a valid SNOMED CT/],
        [{ ...valid, code: 'J4', codeSystem: 'ICD_10' }, 'code', /not a valid ICD-10/],
        [{ ...valid, clinicalStatus: 'CURED' }, 'clinicalStatus', /clinical status/],
        [{ ...valid, verificationStatus: 'REFUTED' }, 'verificationStatus', /verification status/],
        [{ ...valid, onsetDate: '2026-02-30' }, 'onsetDate', /valid onset date/],
        [{ ...valid, onsetDate: '1850-01-01' }, 'onsetDate', /valid onset date/],
        [{ ...valid, onsetDate: '2026-11-13' }, 'onsetDate', /cannot be in the future/],
      ] as const) {
        const checked = form.diagnosisRequest(fields, now)
        assert.equal(checked.ok, false, JSON.stringify(fields).slice(0, 80))
        assert.equal(checked.field, field)
        assert.match(checked.error, message)
      }
    })

    await context.test('a successful save notifies the section, which reloads the list; failures and invalid input do not', async () => {
      const created: unknown[] = []
      const sent: unknown[] = []
      const ok = await form.submitDiagnosis({ ...valid, encounterId: 'enc-2' }, {
        create: async (request: unknown) => { sent.push(request); return diagnoses[0] },
        onCreated: (diagnosis: unknown) => created.push(diagnosis),
      }, now)
      assert.deepEqual(ok, { ok: true, created: diagnoses[0] })
      assert.deepEqual(created, [diagnoses[0]])
      assert.deepEqual(sent, [{ diagnosis: 'Typ 2-diabetes', clinicalStatus: 'ACTIVE', verificationStatus: 'CONFIRMED',
        encounterId: 'enc-2' }])

      const failed = await form.submitDiagnosis({ ...valid, encounterId: 'enc-x' }, {
        create: async () => { throw new ApiError(422, 'x', '/api/patients/p/conditions', 'ENCOUNTER_NOT_FOR_PATIENT') },
        onCreated: () => assert.fail('must not reload'),
      }, now)
      assert.deepEqual(failed, { ok: false, field: 'encounterId',
        error: 'The selected encounter does not belong to this patient. Reload the page and choose again.' })

      let requests = 0
      const invalid = await form.submitDiagnosis({ ...valid, diagnosis: '' }, {
        create: async () => { requests += 1; return diagnoses[0] },
        onCreated: () => assert.fail('must not reload'),
      }, now)
      assert.equal(invalid.ok, false)
      assert.equal(requests, 0)
    })

    await context.test('a second submit while saving is ignored (shared guard)', async () => {
      const guard = notesForm.createSubmitGuard()
      let release: () => void = () => {}
      let runs = 0
      const first = guard(() => new Promise<string>((resolve) => { runs += 1; release = () => resolve('saved') }))
      assert.equal(await guard(async () => { runs += 1; return 'duplicate' }), null)
      release()
      assert.equal(await first, 'saved')
      assert.equal(runs, 1)
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
        return new Response(JSON.stringify(options?.method === 'POST' ? diagnoses[0] : diagnoses),
          { status: options?.method === 'POST' ? 201 : 200 })
      }
      assert.deepEqual(await service.listPatientDiagnoses('syn/p 1'), diagnoses)
      assert.deepEqual(await service.listOwnDiagnoses(), diagnoses)
      const request = { diagnosis: 'Typ 2-diabetes', code: '44054006', codeSystem: 'SNOMED_CT', clinicalStatus: 'ACTIVE',
        verificationStatus: 'CONFIRMED' }
      assert.deepEqual(await service.createDiagnosis('syn-patient-1', request), diagnoses[0])
      assert.deepEqual(calls.map((call) => `${call.method} ${call.url.slice(API_BASE_URL.length)}`), [
        'GET /api/patients/syn%2Fp%201/conditions',
        'GET /api/patients/me/conditions',
        'GET /api/auth/csrf',
        'POST /api/patients/syn-patient-1/conditions',
      ])
      assert.equal(calls[3].headers['X-XSRF-TOKEN'], 'csrf-token')
      assert.deepEqual(JSON.parse(calls[3].body ?? ''), request)
    })

    await context.test('errors are explained without technical details; an unconfirmed save is not called failed', () => {
      const message = (status: number, code?: string, action = 'create') =>
        form.diagnosisErrorMessage(new ApiError(status, 'x', '/api/patients/p/conditions', code), action)
      assert.match(message(422, 'PRACTITIONER_NOT_LINKED'), /not linked to a practitioner/)
      assert.match(message(400, 'CODE_INVALID'), /not valid in the selected code system/)
      assert.match(message(404, 'RECORD_NOT_LINKED', 'load'), /do not have access to a medical record/)
      assert.match(message(403), /Only doctor and staff/)
      assert.match(message(403, undefined, 'load'), /do not have access to these diagnoses/)
      assert.match(message(404), /could not be found/)
      for (const status of [502, 503, 504]) assert.match(message(status), /may or may not have been saved\. Refresh/)
      assert.match(message(503, undefined, 'load'), /temporarily unavailable/)
      assert.match(form.diagnosisErrorMessage(new TypeError('Failed to fetch'), 'create'), /may not have been saved/)
      assert.equal(form.formatClinicalStatus(null), null)
      assert.equal(form.formatVerificationStatus('entered-in-error'), 'Entered in error')
      assert.equal(form.formatCategory('problem-list-item'), 'Problem list')
      assert.equal(form.formatDiagnosisCode({ system: 'urn:x', systemName: null, code: 'X1', display: null }), 'X1')
    })

    await context.test('My Medical Record shows diagnoses read-only; notes, encounters and profiles keep working', () => {
      const state = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {} }
      const as = (role: string) => ({ ...state, user: { id: 7, email: 'user@example.test', role } })
      const record = renderRoute(as('PATIENT'), '/patient/record')
      assert.match(record, /<h1 id="my-record-title">My Medical Record<\/h1>/)
      assert.match(record, /diagnoses and clinical notes/)
      assert.doesNotMatch(record, /Add Diagnosis/)
      for (const role of ['DOCTOR', 'STAFF']) {
        assert.match(renderRoute(as(role), '/patients/syn-patient-1'), /Loading patient profile/)
      }
      assert.match(renderRoute(as('PATIENT'), '/patients/syn-patient-1'), /available to doctor or staff accounts only/)
      assert.match(encounterRender.renderSection({ status: 'loaded', encounters: visits }, 'syn-patient-1'),
        /Create Encounter<\/button>/)
      assert.match(noteRender.renderNoteSection({ status: 'loaded', notes: [] }, 'syn-patient-1'), /Add Clinical Note<\/button>/)
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
