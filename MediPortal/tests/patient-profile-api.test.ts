import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('patient profile API and error messages', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const { getPatient } = await server.ssrLoadModule('/src/features/patientmanagement/patientService.ts')
    const { patientProfileErrorMessage } = await server.ssrLoadModule('/src/features/patientmanagement/patientProfileErrorMessage.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    // Synthetic test data only.
    const profile = {
      id: 'syn-profile-41',
      identifiers: [{ system: 'http://electronichealth.se/identifier/personnummer', value: '199001012385' }],
      names: [{ use: 'official', text: null, family: 'Testsson', given: ['Tolvan'] }],
      gender: 'female',
      birthDate: '1990-01-01',
    }

    await context.test('GETs one profile with the session cookie, no CSRF request and the abort signal', async () => {
      const calls: string[] = []
      const controller = new AbortController()
      globalThis.fetch = async (url, options) => {
        calls.push(`${options?.method} ${url}`)
        assert.equal(options?.credentials, 'include')
        assert.equal(options?.signal, controller.signal)
        return new Response(JSON.stringify(profile))
      }
      assert.deepEqual(await getPatient('syn-profile-41', { signal: controller.signal }), profile)
      assert.deepEqual(calls, [`GET ${API_BASE_URL}/api/patients/syn-profile-41`])
    })

    await context.test('encodes the ID as one path segment', async () => {
      let requested = ''
      globalThis.fetch = async (url) => {
        requested = String(url)
        return new Response(JSON.stringify(profile))
      }
      await getPatient('a/../b?x=1#y')
      assert.equal(requested, `${API_BASE_URL}/api/patients/a%2F..%2Fb%3Fx%3D1%23y`)
    })

    await context.test('rejects HTTP errors with their status', async () => {
      for (const status of [400, 401, 403, 404, 502, 503]) {
        globalThis.fetch = async () => new Response(null, { status })
        await assert.rejects(getPatient('syn-profile-41'), (error: unknown) => error instanceof ApiError && error.status === status)
      }
    })

    await context.test('maps statuses to messages without patient data', () => {
      const expected: Record<number, RegExp> = {
        400: /patient ID is not valid/,
        401: /Sign in again/,
        403: /Only doctor and staff/,
        404: /No patient with this ID/,
        502: /temporarily unavailable/,
        503: /temporarily unavailable/,
        500: /could not be loaded/,
      }
      for (const [status, message] of Object.entries(expected)) {
        const text = patientProfileErrorMessage(new ApiError(Number(status), 'Details', '/api/patients/syn-profile-41'))
        assert.match(text, message)
        assert.doesNotMatch(text, /syn-profile-41|199001012385|Testsson/)
      }
      for (const error of [new TypeError('Failed to fetch'), null, undefined]) {
        assert.match(patientProfileErrorMessage(error), /Unable to reach MediCore/)
      }
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
