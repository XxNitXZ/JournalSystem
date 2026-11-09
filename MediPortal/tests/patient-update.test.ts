import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

// Fictional patient only.
const profile = {
  id: 'syn-upd-1',
  identifiers: [{ system: 'http://electronichealth.se/identifier/personnummer', value: '200412201971' }],
  names: [
    { use: 'nickname', text: null, family: null, given: ['Alle'] },
    { use: 'official', text: null, family: 'Khan', given: ['Ali', 'Reza'] },
  ],
  gender: 'male',
  birthDate: '2004-12-20',
  email: 'ali@example.test',
  version: '2',
}


test('Edit Patient: permissions, form, validation, API and errors', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const update = await server.ssrLoadModule('/src/features/patientmanagement/patientUpdate.ts')
    const { updatePatient } = await server.ssrLoadModule('/src/features/patientmanagement/patientService.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const render = await server.ssrLoadModule('/tests/patient-update-render.tsx')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')

    await context.test('Edit Patient appears for doctors and staff only', () => {
      for (const role of ['DOCTOR', 'STAFF']) {
        assert.equal(update.canEditPatients(role), true)
        const html = render.renderProfileWithActions(profile, role)
        assert.match(html, /<button[^>]*>.*Edit Patient<\/button>/)
      }
      for (const role of ['PATIENT', undefined, null]) {
        assert.equal(update.canEditPatients(role), false)
      }
      assert.doesNotMatch(render.renderProfileWithActions(profile, 'PATIENT'), /Edit Patient/)
      assert.doesNotMatch(render.renderProfileWithActions(profile, undefined), /Edit Patient/)
      const withContact = render.renderProfileWithActions({ ...profile, phoneNumber: '+46701234567' }, 'DOCTOR')
      assert.match(withContact, /<dt>Email<\/dt><dd>ali@example\.test<\/dd><dt>Phone<\/dt><dd>\+46701234567<\/dd>/)
      const without = render.renderProfileWithActions({ ...profile, email: null, phoneNumber: null }, 'STAFF')
      assert.match(without, /<dt>Email<\/dt><dd>Not available<\/dd><dt>Phone<\/dt><dd>Not available<\/dd>/)
      // Neither the record ID nor its version is ever shown.
      assert.doesNotMatch(render.renderProfileWithActions(profile, 'DOCTOR'), /syn-upd-1|version/)
    })

    await context.test('the form is prefilled and only the gender is editable', () => {
      const html = render.renderEditForm(profile)
      assert.match(html, /<h2 id="edit-patient-title">Edit Patient<\/h2>/)
      assert.match(html, /<option value="MALE" selected="">Male<\/option>/)
      // Name (the official one, not the nickname), personnummer and birth date are shown read-only.
      assert.match(html, /<dt>First name<\/dt><dd>Ali Reza<\/dd><dt>Last name<\/dt><dd>Khan<\/dd>/)
      assert.match(html, /20041220-1971/)
      assert.match(html, /2004-12-20/)
      assert.match(html, /cannot be changed after registration/)
      for (const forbidden of ['givenName', 'familyName', 'personnummer', 'birthDate', 'id', 'version', 'email',
        'phoneNumber']) {
        assert.ok(!html.includes(`name="${forbidden}"`), `form field ${forbidden}`)
      }
      // Exactly one form control: the gender.
      assert.equal((html.match(/<(input|select|textarea)\b/g) ?? []).length, 1)
      assert.match(html, /<select[^>]*name="gender"/)
      assert.doesNotMatch(html, /syn-upd-1/)
      assert.match(html, /Save Changes/)
      assert.match(html, /Cancel/)
    })

    await context.test('cancel discards changes: the form always starts from the profile', () => {
      // The change lives only in the open form; after Cancel it is rebuilt from the profile's value.
      assert.deepEqual(update.editableFields(profile), { gender: 'MALE' })
      assert.match(render.renderEditForm(profile), /<option value="MALE" selected="">/)
      assert.match(render.renderEditForm({ ...profile, gender: 'female' }), /<option value="FEMALE" selected="">/)
      assert.deepEqual(update.editableFields({ ...profile, gender: null }), { gender: '' })
      assert.deepEqual(update.editableFields({ ...profile, gender: 'something-else' }), { gender: '' })
    })

    await context.test('saving shows a loading state and errors', () => {
      const saving = render.renderEditForm(profile, true)
      assert.match(saving, /<fieldset disabled="">/)
      assert.match(saving, /aria-busy="true"/)
      assert.match(saving, /Saving changes…/)
      const failed = render.renderEditForm(profile, false, 'The patient information could not be saved.')
      assert.match(failed, /role="alert"[^>]*>.*could not be saved/)
    })

    await context.test('validates the gender and sends nothing else', () => {
      for (const gender of ['FEMALE', 'MALE', 'OTHER', 'UNKNOWN']) {
        assert.equal(update.validatePatientUpdate({ gender }), null)
        assert.deepEqual(update.toUpdateRequest({ gender }), { gender })
      }
      for (const gender of ['', 'female', 'NONBINARY']) {
        assert.equal(update.validatePatientUpdate({ gender }), 'Select a gender.')
      }
    })

    await context.test('saving PATCHes only the gender with the version and a CSRF token', async () => {
      const calls: { method: string; url: string; headers: Record<string, string>; body?: string }[] = []
      globalThis.fetch = async (url, options) => {
        calls.push({ method: String(options?.method), url: String(url),
          headers: options?.headers as Record<string, string>, body: options?.body as string | undefined })
        assert.equal(options?.credentials, 'include')
        if (String(url).endsWith('/api/auth/csrf')) {
          return new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
        }
        return new Response(JSON.stringify({ ...profile, version: '3' }))
      }
      const request = { gender: 'FEMALE' }
      const result = await updatePatient('syn/upd 1', request, '2')
      assert.equal(result.version, '3')
      assert.deepEqual(calls.map((call) => `${call.method} ${call.url}`), [
        `GET ${API_BASE_URL}/api/auth/csrf`,
        `PATCH ${API_BASE_URL}/api/patients/syn%2Fupd%201`,
      ])
      assert.equal(calls[1].headers['If-Match'], '"2"')
      assert.equal(calls[1].headers['X-XSRF-TOKEN'], 'csrf-token')
      assert.equal(calls[1].headers['Content-Type'], 'application/json')
      assert.deepEqual(JSON.parse(calls[1].body ?? ''), request)
    })

    await context.test('failed updates keep their status and code and are never reported as success', async () => {
      globalThis.fetch = async (url) => String(url).endsWith('/api/auth/csrf')
        ? new Response(JSON.stringify({ token: 't', headerName: 'X-XSRF-TOKEN' }))
        : new Response(JSON.stringify({ code: 'VERSION_CONFLICT' }), { status: 409 })
      await assert.rejects(updatePatient('syn-upd-1', { gender: 'MALE' }, '1'),
        (error: unknown) => error instanceof ApiError && error.status === 409 && error.code === 'VERSION_CONFLICT')

      const message = (status: number, code?: string) =>
        update.patientUpdateErrorMessage(new ApiError(status, 'x', '/api/patients/syn-upd-1', code))
      assert.match(message(409, 'VERSION_CONFLICT'), /changed by someone else/)
      assert.match(message(400), /rejected/)
      assert.match(message(403), /Only doctor and staff/)
      assert.match(message(404), /could not be found/)
      assert.match(message(428), /could not be saved/)
      assert.match(message(503), /may not have been saved/)
      assert.match(message(502), /temporarily unavailable/)
      assert.match(update.patientUpdateErrorMessage(new TypeError('Failed to fetch')), /Unable to reach MediCore/)
    })

    await context.test('the profile route stays for doctors and staff only', () => {
      const state = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {} }
      const as = (role: string) => ({ ...state, user: { id: 7, email: 'user@example.test', role } })
      for (const role of ['DOCTOR', 'STAFF']) {
        assert.match(renderRoute(as(role), '/patients/syn-upd-1'), /Loading patient profile/)
      }
      const patient = renderRoute(as('PATIENT'), '/patients/syn-upd-1')
      assert.match(patient, /available to doctor or staff accounts only/)
      assert.doesNotMatch(patient, /Edit Patient/)
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
