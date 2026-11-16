import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

// Fictional identities only (19th-century personnummer, example.test addresses).
const summary = {
  id: 5,
  givenName: 'Ali',
  familyName: 'Khan',
  personnummer: '182003151236',
  email: 'ali@example.test',
  status: 'PENDING',
  createdAt: '2026-11-09T09:00:00Z',
  reviewedAt: null,
}

const detail = {
  id: 5,
  status: 'PENDING',
  createdAt: '2026-11-09T09:00:00Z',
  reviewedAt: null,
  account: { givenName: 'Ali', familyName: 'Khan', personnummer: '182003151236', email: 'ali@example.test' },
  record: { givenNames: ['Ali'], familyName: 'Khan', personnummer: '182003151236', gender: 'male', birthDate: '1820-03-15' },
  recordStatus: 'AVAILABLE',
  personnummerMatches: true,
}

const buttons = (html: string) => [...html.matchAll(/<button[^>]*>.*?<\/button>/g)].map((match) => match[0])

test('journal access texts, rendering and routes', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  try {
    const text = await server.ssrLoadModule('/src/features/journalaccess/journalAccessText.ts')
    const { ApiError } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const render = await server.ssrLoadModule('/tests/journal-access-render.tsx')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')

    await context.test('the PATIENT dashboard shows the exact status texts', () => {
      const expected = {
        NONE: 'You have not requested access to your medical record.',
        AWAITING_RECORD: 'Your request has been received. We are waiting for your medical record to be registered.',
        PENDING: 'Your journal access request is awaiting doctor approval.',
        APPROVED: 'You have access to your medical record.',
        REJECTED: 'Your journal access request was not approved.',
      }
      for (const [status, message] of Object.entries(expected)) {
        assert.equal(text.patientStatusText[status], message)
        const html = render.renderStatus(status)
        assert.ok(html.includes(`<span>${message}</span>`), `missing text for ${status}`)
        assert.match(html, /role="status"/)
        // Only approved access leads to the record.
        assert.equal(html.includes('href="/patient/record"'), status === 'APPROVED')
        assert.equal(html.includes('My Medical Record'), status === 'APPROVED')
        // Only a patient who has not asked yet can ask; a rejection is never resubmitted from here.
        assert.equal(html.includes('Request Journal Access'), status === 'NONE', `request button for ${status}`)
        assert.equal(html.includes('contact your care provider'), status === 'REJECTED')
      }
    })

    await context.test('the request button is disabled while the request is being sent', () => {
      const idle = buttons(render.renderStatus('NONE'))
      assert.equal(idle.length, 1)
      assert.doesNotMatch(idle[0], /disabled/)
      const sending = buttons(render.renderStatus('NONE', true))
      assert.match(sending[0], /disabled/)
      assert.match(sending[0], /Sending request/)
    })

    await context.test('explains why a request could not be sent, without personal data', () => {
      const error = (status: number, code?: string) => new ApiError(status, 'x', '/api/journal-access/me/request', code)
      assert.match(text.requestAccessErrorMessage(error(409, 'REQUEST_REJECTED')), /not approved/)
      assert.match(text.requestAccessErrorMessage(error(409, 'PERSONNUMMER_MISSING')), /no personnummer/)
      assert.match(text.requestAccessErrorMessage(error(409, 'LINK_CONFLICT')), /contact your care provider/)
      assert.match(text.requestAccessErrorMessage(error(409)), /could not be sent/)
      assert.match(text.requestAccessErrorMessage(error(403)), /Only patient accounts/)
      assert.match(text.requestAccessErrorMessage(error(503)), /could not be sent/)
      assert.match(text.requestAccessErrorMessage(new TypeError('Failed to fetch')), /Unable to reach MediCore/)
    })

    await context.test('formats names, personnummer, dates and request IDs safely', () => {
      assert.equal(text.personName(' Ali ', 'Khan'), 'Ali Khan')
      assert.equal(text.personName(null, null), 'Name not on file')
      assert.equal(text.formatPersonnummer('182003151236'), '18200315-1236')
      assert.equal(text.formatPersonnummer(null), 'Not on file')
      assert.equal(text.formatDateTime(null), '—')
      assert.equal(text.formatDateTime('not a date'), '—')
      assert.match(text.formatDateTime('2026-11-09T09:00:00Z'), /^2026-11-09 \d{2}:\d{2}$/)
      assert.equal(text.parseRequestId('42'), 42)
      for (const invalid of [undefined, '', '0', '-1', '01', '1.5', 'abc', '1e3', '99999999999999999']) {
        assert.equal(text.parseRequestId(invalid), null, `accepted ${invalid}`)
      }
    })

    await context.test('maps review errors by status and code without personal data', () => {
      const error = (status: number, code?: string) => new ApiError(status, 'x', '/api/journal-access-requests/5', code)
      assert.match(text.journalAccessErrorMessage(error(409, 'PERSONNUMMER_MISMATCH')), /do not match/)
      assert.match(text.journalAccessErrorMessage(error(409, 'FHIR_PATIENT_MISSING')), /no longer exists/)
      assert.match(text.journalAccessErrorMessage(error(409, 'LINK_CONFLICT')), /already linked/)
      assert.match(text.journalAccessErrorMessage(error(409, 'REQUEST_NOT_PENDING')), /already been decided/)
      assert.match(text.journalAccessErrorMessage(error(409, 'ALREADY_PENDING')), /already awaiting review/)
      assert.match(text.journalAccessErrorMessage(error(409, 'REQUEST_NOT_REJECTED')), /Only rejected/)
      assert.match(text.journalAccessErrorMessage(error(409, 'AWAITING_RECORD')), /cannot be decided/)
      assert.match(text.journalAccessErrorMessage(error(409)), /could not be updated/)
      assert.match(text.journalAccessErrorMessage(error(409, 'UNKNOWN_CODE')), /could not be updated/)
      assert.match(text.journalAccessErrorMessage(error(403)), /Only doctor accounts/)
      assert.match(text.journalAccessErrorMessage(error(404)), /not found/)
      assert.match(text.journalAccessErrorMessage(error(503)), /temporarily unavailable/)
      assert.match(text.journalAccessErrorMessage(new TypeError('Failed to fetch')), /Unable to reach MediCore/)
      assert.match(text.ownRecordErrorMessage(error(404, 'RECORD_NOT_LINKED')), /do not have access/)
      assert.match(text.ownRecordErrorMessage(error(404, 'RECORD_NOT_FOUND')), /could not be found/)
      assert.match(text.ownRecordErrorMessage(error(403)), /Only patient accounts/)
      assert.match(text.ownRecordErrorMessage(error(502)), /temporarily unavailable/)
    })

    await context.test('the request list shows the account data and a review link, never record IDs', () => {
      const html = render.renderList([summary, { ...summary, id: 6, status: 'REJECTED', reviewedAt: '2026-11-09T10:00:00Z',
        givenName: null, familyName: null }])
      assert.match(html, /Ali Khan/)
      assert.match(html, /18200315-1236/)
      assert.match(html, /ali@example\.test/)
      assert.match(html, /Name not on file/)
      assert.match(html, /badge badge--warning">Pending/)
      assert.match(html, /badge badge--danger">Rejected/)
      assert.match(render.renderList([{ ...summary, id: 7, status: 'AWAITING_RECORD' }]), /<span class="badge">Awaiting record/)
      assert.ok(html.includes('href="/doctor/journal-requests/5"'))
      assert.ok(html.includes('href="/doctor/journal-requests/6"'))
      assert.doesNotMatch(html, /fhir|syn-/i)
    })

    await context.test('a matching pending request can be approved or rejected', () => {
      const html = render.renderReview(detail)
      assert.match(html, /MediCore account/)
      assert.match(html, /Patient record/)
      assert.match(html, /1820-03-15/)
      assert.match(html, /<dd>Male<\/dd>/)
      assert.match(html, /personnummer of the account and the patient record match/)
      const [approve, reject] = buttons(html)
      assert.match(approve, /Approve access/)
      assert.doesNotMatch(approve, /disabled/)
      assert.match(reject, /Reject/)
      assert.doesNotMatch(reject, /disabled/)
      assert.equal(buttons(html).length, 2)
      // While a decision is being sent, both buttons are disabled.
      for (const button of buttons(render.renderReview(detail, true))) assert.match(button, /disabled/)
    })

    await context.test('approval is disabled when the record is missing or does not match', () => {
      const mismatch = render.renderReview({ ...detail, personnummerMatches: false,
        record: { ...detail.record, personnummer: '182511044568' } })
      assert.match(mismatch, /do not match/)
      assert.match(buttons(mismatch)[0], /disabled=""[^>]*>.*Approve access/)
      assert.doesNotMatch(buttons(mismatch)[1], /disabled/)

      const missing = render.renderReview({ ...detail, record: null, recordStatus: 'NOT_FOUND', personnummerMatches: false })
      assert.match(missing, /no longer exists/)
      assert.match(buttons(missing)[0], /disabled/)
      const unavailable = render.renderReview({ ...detail, record: null, recordStatus: 'UNAVAILABLE', personnummerMatches: false })
      assert.match(unavailable, /could not be loaded right now/)
      assert.match(buttons(unavailable)[0], /disabled/)
    })

    await context.test('a request awaiting its record cannot be decided', () => {
      const html = render.renderReview({ ...detail, status: 'AWAITING_RECORD', record: null, recordStatus: 'NOT_REGISTERED',
        personnummerMatches: false })
      assert.equal(buttons(html).length, 0)
      assert.match(html, /No patient record with this personnummer has been registered yet/)
      assert.match(html, /Awaiting record/)
      assert.match(html, /18200315-1236/)
    })

    await context.test('decided requests offer only reconsideration after a rejection', () => {
      const rejected = render.renderReview({ ...detail, status: 'REJECTED', reviewedAt: '2026-11-09T10:00:00Z' })
      assert.deepEqual(buttons(rejected).map((button) => /Reconsider request/.test(button)), [true])
      assert.match(rejected, /Reviewed /)
      const approved = render.renderReview({ ...detail, status: 'APPROVED', reviewedAt: '2026-11-09T10:00:00Z' })
      assert.equal(buttons(approved).length, 0)
      assert.match(approved, /badge badge--success">Approved/)
    })

    await context.test('profiles show the linked e-mail or "Not available"', () => {
      const patient = {
        id: 'syn-1',
        identifiers: [{ system: 'http://electronichealth.se/identifier/personnummer', value: '182003151236' }],
        names: [{ use: 'official', text: null, family: 'Khan', given: ['Ali'] }],
        gender: 'male',
        birthDate: '1820-03-15',
      }
      assert.match(render.renderProfile({ ...patient, email: 'ali@example.test' }), /<dt>Email<\/dt><dd>ali@example\.test<\/dd>/)
      assert.match(render.renderProfile({ ...patient, email: null }), /<dt>Email<\/dt><dd>Not available<\/dd>/)
      const own = render.renderProfile({ ...patient, email: 'ali@example.test', phoneNumber: '0701234567' },
        'Your medical record')
      assert.match(own, /Your medical record/)
      // My Medical Record: demographics from the record, contact details from the patient's own account.
      assert.match(own, /<dt>Email<\/dt><dd>ali@example\.test<\/dd><dt>Phone<\/dt><dd>0701234567<\/dd>/)
      assert.match(render.renderProfile({ ...patient, email: 'ali@example.test', phoneNumber: null }),
        /<dt>Phone<\/dt><dd>Not available<\/dd>/)
      for (const html of [own, render.renderProfile({ ...patient, email: null })]) assert.doesNotMatch(html, /syn-1/)
    })

    await context.test('the count badge appears only for pending requests', () => {
      assert.equal(render.renderCount(null), '')
      assert.equal(render.renderCount(0), '')
      assert.match(render.renderCount(3), /<span aria-hidden="true">3<\/span><span class="visually-hidden">3 awaiting review<\/span>/)
      assert.match(render.renderCount(120), />99\+</)
    })

    const state = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {} }
    const as = (role: string) => ({ ...state, user: { id: 42, email: 'user@example.test', role } })

    await context.test('only doctors see and open the journal access request pages', () => {
      const dashboard = renderRoute(as('DOCTOR'), '/doctor')
      assert.ok(dashboard.includes('href="/doctor/journal-requests"'))
      assert.match(dashboard, /Journal access requests/)
      assert.match(dashboard, /Journal requests/)
      assert.match(renderRoute(as('DOCTOR'), '/doctor/journal-requests'), /<h1 id="journal-requests-title">Journal access requests<\/h1>/)
      assert.match(renderRoute(as('DOCTOR'), '/doctor/journal-requests/5'), /<h1 id="journal-review-title">Review request<\/h1>/)
      assert.match(renderRoute(as('DOCTOR'), '/doctor/journal-requests/abc'), /This journal access request was not found/)

      for (const role of ['STAFF', 'PATIENT']) {
        const home = renderRoute(as(role), `/${role.toLowerCase()}`)
        assert.ok(!home.includes('href="/doctor/journal-requests"'), `${role} sees the link`)
        for (const path of ['/doctor/journal-requests', '/doctor/journal-requests/5']) {
          const html = renderRoute(as(role), path)
          assert.match(html, /available to doctor accounts only/)
          assert.doesNotMatch(html, /Review request|Awaiting review/)
        }
      }
      for (const path of ['/doctor/journal-requests', '/doctor/journal-requests/5']) {
        assert.doesNotMatch(renderRoute(state, path), /Review request|journal-requests-title/)
      }
    })

    await context.test('only patients open their own medical record page', () => {
      assert.match(renderRoute(as('PATIENT'), '/patient/record'), /<h1 id="my-record-title">My Medical Record<\/h1>/)
      assert.match(renderRoute(as('PATIENT'), '/patient/record'), /Loading your medical record/)
      for (const role of ['DOCTOR', 'STAFF']) {
        const html = renderRoute(as(role), '/patient/record')
        assert.match(html, /available to patient accounts only/)
        assert.doesNotMatch(html, /my-record-title/)
      }
      assert.doesNotMatch(renderRoute(state, '/patient/record'), /my-record-title/)
    })

    await context.test('the registration forms ask for a personnummer and no date of birth', () => {
      const register = renderRoute(state, '/register')
      assert.ok(register.includes('name="personnummer"'))
      const patient = renderRoute(as('STAFF'), '/patients/new')
      assert.ok(patient.includes('name="personnummer"'))
      assert.ok(!patient.includes('name="birthDate"'))
    })
  } finally {
    await server.close()
  }
})
