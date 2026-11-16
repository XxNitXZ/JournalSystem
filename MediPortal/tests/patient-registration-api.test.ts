import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('patient registration API and error messages', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const { registerPatient } = await server.ssrLoadModule('/src/features/patientmanagement/patientService.ts')
    const { patientErrorMessage } = await server.ssrLoadModule('/src/features/patientmanagement/patientErrorMessage.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    // Synthetic test personnummer (Skatteverket's test series).
    const request = { personnummer: '199001012385', family: 'Testsson', given: ['Tolvan'], gender: 'FEMALE', birthDate: '1990-01-01' }
    const response = {
      id: 'local-1',
      identifiers: [{ system: 'http://electronichealth.se/identifier/personnummer', value: '199001012385' }],
      names: [{ use: 'official', text: null, family: 'Testsson', given: ['Tolvan'] }],
      gender: 'female',
      birthDate: '1990-01-01',
    }

    await context.test('POSTs the patient with CSRF and session cookie and returns the registered patient', async () => {
      const calls: string[] = []
      globalThis.fetch = async (url, options) => {
        calls.push(`${options?.method} ${url}`)
        assert.equal(options?.credentials, 'include')
        if (url === `${API_BASE_URL}/api/auth/csrf`) {
          return new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
        }
        assert.equal(url, `${API_BASE_URL}/api/patients`)
        assert.deepEqual(options?.headers, { Accept: 'application/json', 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'csrf-token' })
        assert.deepEqual(JSON.parse(String(options?.body)), request)
        return new Response(JSON.stringify(response), { status: 201 })
      }
      assert.deepEqual(await registerPatient(request), response)
      assert.deepEqual(calls, [`GET ${API_BASE_URL}/api/auth/csrf`, `POST ${API_BASE_URL}/api/patients`])
    })

    await context.test('rejects HTTP errors once, without retrying', async () => {
      for (const status of [400, 401, 403, 409, 422, 502, 503]) {
        let posts = 0
        globalThis.fetch = async (url, options) => {
          if (url === `${API_BASE_URL}/api/auth/csrf`) {
            return new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
          }
          posts += 1
          assert.equal(options?.method, 'POST')
          return new Response(null, { status })
        }
        await assert.rejects(registerPatient(request), (error: unknown) => error instanceof ApiError && error.status === status)
        assert.equal(posts, 1)
      }
    })

    await context.test('maps statuses to messages that never contain the entered data', () => {
      const expected: Record<number, RegExp> = {
        400: /rejected\. Check the fields/,
        401: /Sign in again/,
        403: /Only doctor and staff/,
        409: /already registered/,
        422: /could not be saved/,
        502: /did not confirm the registration/,
        503: /did not confirm the registration/,
        500: /Patient registration failed/,
      }
      for (const [status, message] of Object.entries(expected)) {
        const text = patientErrorMessage(new ApiError(Number(status), 'Details', '/api/patients'))
        assert.match(text, message)
        assert.doesNotMatch(text, /199001012385|Testsson|Tolvan/)
      }
      for (const error of [new TypeError('Failed to fetch'), new Error('x'), null, undefined]) {
        assert.match(patientErrorMessage(error), /Unable to reach MediCore/)
      }
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
