import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('registration adopts only a session that MediCore started for the new account', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const { registerAccount } = await server.ssrLoadModule('/src/features/auth/registrationService.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const csrf = () => new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))

    function mockServer(register: () => Response, me: () => Response | Promise<Response>) {
      const calls: string[] = []
      globalThis.fetch = async (url, options) => {
        const path = String(url).slice(API_BASE_URL.length)
        calls.push(`${options?.method} ${path}`)
        assert.equal(options?.credentials, 'include')
        if (path === '/api/auth/csrf') return csrf()
        if (path === '/api/auth/register') return register()
        if (path === '/api/auth/me') return me()
        throw new Error(`unexpected ${path}`)
      }
      return calls
    }

    const patient = { id: 42, email: 'new@example.com', role: 'PATIENT' }
    const request = { email: 'new@example.com', password: 'a secure password', role: 'PATIENT' }

    await context.test('a PATIENT registration returns the new session from /api/auth/me', async () => {
      const calls = mockServer(() => new Response(JSON.stringify(patient), { status: 201 }),
        () => new Response(JSON.stringify(patient)))
      assert.deepEqual(await registerAccount(request), { account: patient, session: patient })
      assert.deepEqual(calls, ['GET /api/auth/csrf', 'POST /api/auth/register', 'GET /api/auth/me'])
    })

    for (const role of ['DOCTOR', 'STAFF']) {
      await context.test(`a ${role} registration has no session (401 from /api/auth/me)`, async () => {
        const account = { ...patient, role }
        mockServer(() => new Response(JSON.stringify(account), { status: 201 }), () => new Response(null, { status: 401 }))
        assert.deepEqual(await registerAccount({ ...request, role }), { account, session: null })
      })
    }

    await context.test('a session for another account is never adopted', async () => {
      mockServer(() => new Response(JSON.stringify(patient), { status: 201 }),
        () => new Response(JSON.stringify({ id: 7, email: 'other@example.com', role: 'STAFF' })))
      assert.deepEqual(await registerAccount(request), { account: patient, session: null })
    })

    await context.test('a failed session check after a successful registration means "not signed in"', async () => {
      for (const me of [() => new Response(null, { status: 503 }), () => { throw new TypeError('Network failure') }]) {
        mockServer(() => new Response(JSON.stringify(patient), { status: 201 }), me)
        assert.deepEqual(await registerAccount(request), { account: patient, session: null })
      }
    })

    for (const status of [400, 409, 500]) {
      await context.test(`a failed registration (HTTP ${status}) is rejected and never checks the session`, async () => {
        const calls = mockServer(() => new Response(null, { status }), () => new Response(JSON.stringify(patient)))
        await assert.rejects(registerAccount(request), (error: unknown) => error instanceof ApiError && error.status === status)
        assert.ok(!calls.includes('GET /api/auth/me'))
      })
    }
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
