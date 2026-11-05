import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('registration sends JSON and preserves API errors', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const { registerUser } = await server.ssrLoadModule('/src/features/auth/registrationService.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const request = { email: 'user@example.com', password: 'a password', role: 'DOCTOR' }
    const response = { id: 42, email: request.email, role: request.role }

    await context.test('POSTs only registration data and returns the response', async () => {
      const controller = new AbortController()
      globalThis.fetch = async (url, options) => {
        assert.equal(options?.credentials, 'include')
        if (url === `${API_BASE_URL}/api/auth/csrf`) {
          assert.equal(options?.method, 'GET')
          return new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
        }
        assert.equal(url, `${API_BASE_URL}/api/auth/register`)
        assert.equal(options?.method, 'POST')
        assert.deepEqual(options?.headers, { Accept: 'application/json', 'Content-Type': 'application/json', 'X-CSRF-TOKEN': 'csrf-token' })
        assert.deepEqual(JSON.parse(String(options?.body)), request)
        assert.equal(options?.signal, controller.signal)
        return new Response(JSON.stringify(response), { status: 201 })
      }
      assert.deepEqual(await registerUser(request, { signal: controller.signal }), response)
    })

    for (const status of [400, 409, 500]) {
      await context.test(`preserves HTTP ${status} as ApiError`, async () => {
        globalThis.fetch = async (url) => String(url).endsWith('/csrf')
          ? new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-CSRF-TOKEN' }))
          : new Response(null, { status })
        await assert.rejects(registerUser(request), (error: unknown) => {
          assert.ok(error instanceof ApiError)
          assert.equal((error as { status: number }).status, status)
          return true
        })
      })
    }

    await context.test('propagates network failures unchanged', async () => {
      const failure = new TypeError('Network failure')
      globalThis.fetch = async () => { throw failure }
      await assert.rejects(registerUser(request), (error: unknown) => error === failure)
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
