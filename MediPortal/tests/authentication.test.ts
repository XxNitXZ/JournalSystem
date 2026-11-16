import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('session authentication API and feedback', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const { login, logout, getCurrentUser } = await server.ssrLoadModule('/src/features/auth/authenticationService.ts')
    const { loginErrorMessage } = await server.ssrLoadModule('/src/features/auth/loginErrorMessage.ts')
    const { ApiError } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const user = { id: 42, email: 'user@example.com', role: 'STAFF' }

    await context.test('login, me, and logout send cookies and fresh CSRF tokens', async () => {
      let tokenNumber = 0
      const paths: string[] = []
      globalThis.fetch = async (url, options) => {
        const path = new URL(String(url)).pathname
        paths.push(path)
        assert.equal(options?.credentials, 'include')
        if (path.endsWith('/csrf')) {
          assert.equal(options?.method, 'GET')
          return Response.json({ token: `token-${++tokenNumber}`, headerName: 'X-CSRF-TOKEN' })
        }
        if (options?.method === 'POST') {
          assert.equal(new Headers(options.headers).get('X-CSRF-TOKEN'), `token-${tokenNumber}`)
        }
        if (path.endsWith('/login')) {
          assert.deepEqual(JSON.parse(String(options?.body)), { email: user.email, password: ' password ' })
          return Response.json(user)
        }
        if (path.endsWith('/logout')) return new Response(null, { status: 204 })
        assert.equal(options?.method, 'GET')
        return Response.json(user)
      }
      assert.deepEqual(await login({ email: user.email, password: ' password ' }), user)
      assert.deepEqual(await getCurrentUser(), user)
      assert.equal(await logout(), undefined)
      assert.deepEqual(paths, ['/api/auth/csrf', '/api/auth/login', '/api/auth/me', '/api/auth/csrf', '/api/auth/logout'])
    })

    await context.test('me treats only 401 as an anonymous session', async () => {
      globalThis.fetch = async () => new Response(null, { status: 401 })
      assert.equal(await getCurrentUser(), null)
      globalThis.fetch = async () => new Response(null, { status: 500 })
      await assert.rejects(getCurrentUser(), (error: unknown) => error instanceof ApiError)
    })

    await context.test('failed CSRF bootstrap prevents submitting credentials', async () => {
      let requests = 0
      globalThis.fetch = async (url) => {
        requests++
        assert.ok(String(url).endsWith('/csrf'))
        return new Response(null, { status: 503 })
      }
      await assert.rejects(login({ email: user.email, password: 'secret' }))
      assert.equal(requests, 1)
    })

    await context.test('failed logout is rejected rather than reported as success', async () => {
      globalThis.fetch = async (url) => String(url).endsWith('/csrf')
        ? Response.json({ token: 'token', headerName: 'X-CSRF-TOKEN' })
        : new Response(null, { status: 403 })
      await assert.rejects(logout(), (error: unknown) => error instanceof ApiError)
    })

    await context.test('maps invalid credentials, invalid input, CSRF, server and network errors', () => {
      for (const [status, message] of [
        [401, 'Invalid email or password.'],
        [400, 'Check your email and password.'],
        [403, 'Your security token expired. Please try again.'],
        [500, 'Unable to sign in. Please try again later.'],
      ] as const) {
        assert.equal(loginErrorMessage(new ApiError(status, 'Sensitive details', '/api/auth/login')), message)
      }
      assert.equal(loginErrorMessage(new TypeError('Fetch failed')),
        'Unable to reach the server. Check your connection and try again.')
    })

    await context.test('session requests propagate cancellation', async () => {
      const controller = new AbortController()
      controller.abort()
      globalThis.fetch = async (_url, options) => {
        assert.equal(options?.signal, controller.signal)
        throw controller.signal.reason
      }
      await assert.rejects(getCurrentUser({ signal: controller.signal }))
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
