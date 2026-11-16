import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('clinician inbox API, view, access and stale requests', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const original = globalThis.fetch
  try {
    const api = await server.ssrLoadModule('/src/features/messaging/messageService.ts')
    const { loadInboxRequest } = await server.ssrLoadModule('/src/features/messaging/inboxRequest.ts')
    const { conversationError } = await server.ssrLoadModule('/src/features/messaging/conversationText.ts')
    const { ApiError } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const { renderInbox, renderProfessionalHistory } = await server.ssrLoadModule('/tests/inbox-render.tsx')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const inbox = [{ patientId: 42, displayName: 'Synthetic Patient', messageCount: 2, lastMessageAt: '2026-01-01T00:00:00Z' }]
    await context.test('GETs inbox and selected thread using cookies and abort signals only', async () => {
      const calls: string[] = []
      const controller = new AbortController()
      globalThis.fetch = async (url, options) => {
        calls.push(`${options?.method} ${url}`)
        assert.equal(options?.credentials, 'include')
        assert.equal(options?.signal, controller.signal)
        assert.equal(options?.body, undefined)
        return new Response(JSON.stringify(String(url).endsWith('/42') ? [] : inbox))
      }
      assert.deepEqual(await api.getProfessionalInbox({ signal: controller.signal }), inbox)
      assert.deepEqual(await api.getInboxMessages(42, { signal: controller.signal }), [])
      assert.equal(calls.length, 2)
      assert.ok(calls[0].endsWith('/api/messages/inbox'))
      assert.ok(calls[1].endsWith('/api/messages/inbox/42'))
      assert.ok(calls.every((call) => call.startsWith('GET ')))
    })
    await context.test('late success and failure cannot replace a new selection', async () => {
      let resolveOld!: (value: unknown) => void
      let rejectOld!: (error: unknown) => void
      let signal!: AbortSignal
      const values: unknown[] = []
      const errors: unknown[] = []
      const cancelOld = loadInboxRequest((value: AbortSignal) => {
        signal = value
        return new Promise((resolve) => { resolveOld = resolve })
      }, (value: unknown) => values.push(value), (error: unknown) => errors.push(error))
      cancelOld()
      assert.equal(signal.aborted, true)
      const cancelNew = loadInboxRequest(async () => ['new thread'], (value: unknown) => values.push(value), (error: unknown) => errors.push(error))
      resolveOld(['old thread'])
      const cancelFailure = loadInboxRequest(() => new Promise((_resolve, reject) => { rejectOld = reject }),
        (value: unknown) => values.push(value), (error: unknown) => errors.push(error))
      cancelFailure()
      rejectOld(new Error('old failure'))
      await new Promise((done) => setTimeout(done, 0))
      assert.deepEqual(values, [['new thread']])
      assert.deepEqual(errors, [])
      cancelNew()
    })
    await context.test('current failures are shown and API errors propagate', async () => {
      for (const status of [400, 401, 403, 404, 500]) {
        globalThis.fetch = async () => new Response('', { status })
        await assert.rejects(api.getProfessionalInbox(), (error: unknown) => error instanceof ApiError && error.status === status)
      }
      const errors: string[] = []
      const cancel = loadInboxRequest(async () => { throw new TypeError('network') }, () => assert.fail('unexpected success'),
        (error: unknown) => errors.push(conversationError(error)))
      await new Promise((done) => setTimeout(done, 0))
      assert.match(errors[0], /could not be loaded/)
      assert.match(conversationError(new ApiError(401, '', '')), /sign in/)
      assert.match(conversationError(new ApiError(403, '', '')), /access/)
      cancel()
    })
    await context.test('list renders patient metadata, selected link and empty state', () => {
      for (const role of ['doctor', 'staff']) {
        const html = renderInbox(inbox, `/${role}/inbox`, 42)
        assert.match(html, /Synthetic Patient/)
        assert.match(html, /2 messages/)
        assert.match(html, /Latest:/)
        assert.match(html, /aria-current="page"/)
        assert.ok(html.includes(`href="/${role}/inbox/42"`))
      }
      assert.match(renderInbox([], '/doctor/inbox'), /Your inbox is empty/)
    })
    await context.test('history uses authenticated professional ID and preserves server order and plain text', () => {
      const messages = [
        { id: 9, senderId: 42, recipientId: 7, content: '<script>patient</script>\nline two', createdAt: '2026-01-01T00:00:00.000001Z' },
        { id: 1, senderId: 7, recipientId: 42, content: 'Professional text', createdAt: '2026-01-01T00:00:00.000002Z' },
      ]
      const html = renderProfessionalHistory(messages, 7)
      assert.match(html, /<strong>Synthetic Patient<\/strong>/)
      assert.match(html, /Patient message/)
      assert.match(html, /<strong>You<\/strong>/)
      assert.match(html, /Sent/)
      assert.match(html, /&lt;script&gt;patient/)
      assert.doesNotMatch(html, /<script>/)
      assert.ok(html.indexOf('line two') < html.indexOf('Professional text'))
      assert.match(renderProfessionalHistory([], 7), /no messages/)
    })
    await context.test('DOCTOR and STAFF routes, loading and navigation; other roles blocked', () => {
      const base = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {}, acceptSession: () => {} }
      for (const role of ['DOCTOR', 'STAFF']) {
        const state = { ...base, user: { id: 7, email: 'synthetic@example.test', role } }
        for (const path of [`/${role.toLowerCase()}/inbox`, `/${role.toLowerCase()}/inbox/42`]) {
          assert.match(renderRoute(state, path), /Loading patient inbox/)
          for (const wrong of ['PATIENT', role === 'DOCTOR' ? 'STAFF' : 'DOCTOR']) {
            assert.match(renderRoute({ ...state, user: { ...state.user, role: wrong } }, path), /accounts only/)
          }
          for (const unknown of [base, { ...state, loading: true }, { ...state, error: 'Session unavailable' }]) {
            assert.doesNotMatch(renderRoute(unknown, path), /Loading patient inbox/)
          }
        }
        assert.ok(renderRoute(state, `/${role.toLowerCase()}`).includes(`href="/${role.toLowerCase()}/inbox"`))
      }
    })
  } finally { globalThis.fetch = original; await server.close() }
})
