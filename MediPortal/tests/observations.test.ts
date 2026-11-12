import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('observations form, API, values and role-specific sections', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const original = globalThis.fetch
  try {
    const form = await server.ssrLoadModule('/src/features/medicalrecord/observationForm.ts')
    const api = await server.ssrLoadModule('/src/features/medicalrecord/observationService.ts')
    const { ApiError } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const { renderObservations, renderObservationList } = await server.ssrLoadModule('/tests/observation-render.tsx')
    const valid = { ...form.emptyObservationFields, type: 'Synthetic temperature', date: '2026-06-15', value: '0', unit: 'mg' }
    const item = { id: 'syn-o1', status: 'final', subject: { reference: 'Patient/syn-p1' }, code: { text: '<Synthetic>', coding: [] },
      value: { type: 'QUANTITY', data: { value: 0, comparator: null, unit: 'mg', system: null, code: null } },
      effective: { type: 'DATE_TIME', data: { value: '2026-06' } }, issued: null, dataAbsentReason: null,
      components: [{ code: { text: 'Component', coding: [] }, value: { type: 'BOOLEAN', data: { value: false } }, dataAbsentReason: null }] }
    await context.test('quantity zero/negative and decimal round trips without silent loss', () => {
      for (const text of ['0', '-0.25', '12.500', '+001.20', '0.0000001', '9007199254740991']) assert.notEqual(form.safeObservationNumber(text), null)
      for (const text of ['', 'NaN', 'Infinity', '1e10', '1,5', '9007199254740992', '0.1234567890123456789', '1.0000000000000001']) {
        assert.equal(form.safeObservationNumber(text), null, text)
      }
      assert.equal(form.observationRequest(valid).request.valueQuantity.value, 0)
      assert.equal(form.observationRequest({ ...valid, value: '-2.5' }).request.valueQuantity.value, -2.5)
    })
    await context.test('requires type/date/result, validates real dates and unit system pairing', () => {
      for (const fields of [{ ...valid, type: ' ' }, { ...valid, date: '' }, { ...valid, date: '2026-02-30' }, { ...valid, date: '0000-01-01' },
        { ...valid, value: '' }, { ...valid, system: 'urn:unit' }, { ...valid, code: 'mg' }, { ...valid, system: 'relative', code: 'mg' }]) {
        assert.ok('error' in form.observationRequest(fields))
      }
      assert.ok('request' in form.observationRequest({ ...valid, system: 'http://unitsofmeasure.org', code: 'mg' }))
      for (const resultType of ['coded', 'absent']) {
        const checked = form.observationRequest({ ...valid, resultType, resultText: 'Synthetic result' })
        assert.equal(checked.request.valueQuantity, undefined)
        assert.equal(checked.request[resultType === 'coded' ? 'valueCodeableConcept' : 'dataAbsentReason'].text, 'Synthetic result')
        assert.equal(Object.keys(checked.request).filter((key) => ['valueQuantity', 'valueCodeableConcept', 'dataAbsentReason'].includes(key)).length, 1)
        assert.ok('error' in form.observationRequest({ ...valid, resultType, resultText: ' ' }))
      }
    })
    await context.test('GET own vs selected patient, POST minimal payload with cookies/CSRF/signal', async () => {
      const calls: string[] = []
      const controller = new AbortController()
      globalThis.fetch = async (url, options) => {
        calls.push(`${options?.method} ${url}`)
        assert.equal(options?.credentials, 'include')
        assert.equal(options?.signal, controller.signal)
        if (String(url).endsWith('/csrf')) return new Response(JSON.stringify({ token: 'synthetic', headerName: 'X-CSRF-TOKEN' }))
        if (options?.method === 'POST') {
          assert.equal((options.headers as Record<string, string>)['X-CSRF-TOKEN'], 'synthetic')
          assert.deepEqual(JSON.parse(String(options.body)), form.observationRequest(valid).request)
          return new Response(JSON.stringify(item), { status: 201 })
        }
        return new Response(JSON.stringify({ observations: [item], complete: true, excludedCount: 0 }))
      }
      const options = { signal: controller.signal }
      await api.listOwnObservations(options)
      await api.listPatientObservations('syn/p1', options)
      assert.deepEqual(await api.createObservation('syn/p1', form.observationRequest(valid).request, options), item)
      assert.ok(calls[0].endsWith('/api/patients/me/observations'))
      assert.ok(calls[1].endsWith('/api/patients/syn%2Fp1/observations'))
      assert.ok(calls[3].startsWith('POST '))
    })
    await context.test('duplicate guard, failed drafts, confirmed creation and cancellation', async () => {
      let release!: () => void
      const guard = { current: false }
      const controller = new AbortController()
      let calls = 0, confirmed = 0
      const deps = { send: () => { calls++; return new Promise<void>((done) => { release = done }) }, confirmed: () => { confirmed++ } }
      const pending = form.submitObservation(valid, guard, controller.signal, deps)
      await form.submitObservation(valid, guard, controller.signal, deps)
      assert.equal(calls, 1)
      release(); await pending
      assert.equal(confirmed, 1)
      assert.equal(guard.current, false)
      const snapshot = structuredClone(valid)
      const error = await form.submitObservation(valid, guard, controller.signal, { send: async () => { throw new Error('network') }, confirmed: () => assert.fail() })
      assert.ok(error)
      assert.deepEqual(valid, snapshot)
      const stale = form.submitObservation(valid, guard, controller.signal, deps)
      controller.abort(); release(); await stale
      assert.equal(confirmed, 1)
    })
    await context.test('confirmed creation remains successful if refreshing fails without retrying POST', async () => {
      let posts = 0, confirmed = false, refreshFailed = false
      let refresh: Promise<unknown> = Promise.resolve()
      globalThis.fetch = async (url, options) => {
        if (String(url).endsWith('/csrf')) return new Response(JSON.stringify({ token: 'test', headerName: 'X-CSRF-TOKEN' }))
        if (options?.method === 'POST') { posts++; return new Response(JSON.stringify(item), { status: 201 }) }
        return new Response('', { status: 503 })
      }
      const result = await form.submitObservation(valid, { current: false }, new AbortController().signal, {
        send: (request: unknown) => api.createObservation('syn-p1', request),
        confirmed: () => {
          confirmed = true
          refresh = api.listPatientObservations('syn-p1').catch(() => { refreshFailed = true })
        },
      })
      await refresh
      assert.equal(result, null)
      assert.equal(confirmed, true)
      assert.equal(refreshFailed, true)
      assert.equal(posts, 1)
    })

    await context.test('typed values preserve false/zero, periods, ranges, ratio and missing data', () => {
      const q = { value: -1, comparator: '<', unit: 'mg', system: null, code: null }
      for (const [value, expected] of [
        [{ type: 'QUANTITY', data: q }, '< -1 mg'], [{ type: 'BOOLEAN', data: { value: false } }, 'false'],
        [{ type: 'INTEGER', data: { value: 0 } }, '0'], [{ type: 'STRING', data: { value: 'text' } }, 'text'],
        [{ type: 'TIME', data: { value: '12:00:00' } }, '12:00:00'], [{ type: 'DATE_TIME', data: { value: '2026' } }, '2026'],
        [{ type: 'PERIOD', data: { start: '2026', end: null } }, '2026 – Ongoing'],
        [{ type: 'RANGE', data: { low: q, high: null } }, '< -1 mg – Not recorded'],
        [{ type: 'RATIO', data: { numerator: q, denominator: q } }, '< -1 mg / < -1 mg'],
        [{ type: 'CODEABLE_CONCEPT', data: { text: null, coding: [{ system: 'urn:test', code: 'x', display: 'Synthetic' }] } }, 'Synthetic'],
        [null, 'Not recorded'],
      ]) assert.equal(form.observationValue(value), expected)
    })
    await context.test('read-only and clinician sections render controls, components and safe text', () => {
      const state = { status: 'loaded', complete: true, excludedCount: 0, observations: [item] }
      const own = renderObservations(state)
      assert.doesNotMatch(own, /Add Observation|Record observation/)
      assert.match(own, /Component: false/)
      assert.match(own, /0 mg/)
      assert.match(own, /2026-06/)
      assert.match(own, /&lt;Synthetic&gt;/)
      assert.match(renderObservations(state, 'syn-p1'), /Add Observation/)
      const creating = renderObservations(state, 'syn-p1', true)
      assert.match(creating, /type="date"/)
      assert.match(creating, /Numeric value/)
      assert.doesNotMatch(creating, /name="patientId"/)
      assert.match(renderObservationList({ status: 'loading' }), /Loading observations/)
      assert.match(renderObservationList({ status: 'failed', message: 'Unavailable' }), /role="alert"/)
      assert.match(renderObservationList({ status: 'loaded', complete: true, excludedCount: 0, observations: [] }), /No observations/)
      assert.match(renderObservationList({ status: 'loaded', complete: true, excludedCount: 0, observations: [{ ...item, value: null, dataAbsentReason: { text: 'Unknown', coding: [] } }] }), /Not measured: Unknown/)
    })
    await context.test('incomplete envelopes retain metadata and always warn, even with no valid observations', async () => {
      const partial = { observations: [item], complete: false, excludedCount: 1 }
      globalThis.fetch = async () => new Response(JSON.stringify(partial))
      assert.deepEqual(await api.listOwnObservations(), partial)
      assert.deepEqual(await api.listPatientObservations('syn-p1'), partial)
      const mixed = renderObservationList({ status: 'loaded', ...partial })
      assert.match(mixed, /role="alert"/)
      assert.match(mixed, /This list is incomplete/)
      assert.match(mixed, /1 observation was excluded/)
      assert.match(mixed, /0 mg/)
      const excluded = renderObservationList({ status: 'loaded', observations: [], complete: false, excludedCount: 2 })
      assert.match(excluded, /This list is incomplete/)
      assert.match(excluded, /No valid observations could be displayed/)
      assert.doesNotMatch(excluded, /No observations have been recorded yet/)
      const empty = renderObservationList({ status: 'loaded', observations: [], complete: true, excludedCount: 0 })
      assert.match(empty, /No observations have been recorded yet/)
      assert.doesNotMatch(empty, /incomplete|excluded/)
    })

    await context.test('HTTP/network errors and request cancellation propagate safely', async () => {
      for (const status of [400, 401, 403, 404, 422, 500, 503]) {
        globalThis.fetch = async () => new Response('', { status })
        await assert.rejects(api.listOwnObservations(), (error: unknown) => error instanceof ApiError && error.status === status)
        assert.ok(form.observationError(new ApiError(status, '', '')).length)
      }
      const controller = new AbortController()
      globalThis.fetch = async (_url, options) => new Promise((_resolve, reject) => {
        options?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
      const pending = api.listOwnObservations({ signal: controller.signal }); controller.abort()
      await assert.rejects(pending, { name: 'AbortError' })
    })
  } finally { globalThis.fetch = original; await server.close() }
})
