import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('clinician replies, refresh and lifecycle safety', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const original = globalThis.fetch
  try {
    const api = await server.ssrLoadModule('/src/features/messaging/messageService.ts')
    const { submitReply } = await server.ssrLoadModule('/src/features/messaging/replyForm.ts')
    const { ApiError } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const { renderReply, renderPatientReply } = await server.ssrLoadModule('/tests/reply-render.tsx')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const sent = { id: 5, createdAt: '2026-01-01T00:00:00Z' }
    await context.test('correct reply path, content-only JSON, CSRF, cookies and cancellation signal', async () => {
      const calls: string[] = []
      const controller = new AbortController()
      globalThis.fetch = async (url, options) => {
        calls.push(`${options?.method} ${url}`)
        assert.equal(options?.credentials, 'include')
        assert.equal(options?.signal, controller.signal)
        if (options?.method === 'GET') return new Response(JSON.stringify({ token: 'synthetic', headerName: 'X-CSRF-TOKEN' }))
        assert.ok(String(url).endsWith('/api/messages/inbox/42/reply'))
        assert.deepEqual(JSON.parse(String(options?.body)), { content: ' reply ' })
        assert.equal((options?.headers as Record<string, string>)['X-CSRF-TOKEN'], 'synthetic')
        return new Response(JSON.stringify(sent), { status: 201 })
      }
      assert.deepEqual(await api.sendReply(42, ' reply ', { signal: controller.signal }), sent)
      assert.equal(calls.length, 2)
    })
    await context.test('successful send confirms before refreshing both thread and inbox', async () => {
      const calls: string[] = []
      let posts = 0
      globalThis.fetch = async (url, options) => {
        if (String(url).endsWith('/csrf')) return new Response(JSON.stringify({ token: 'test', headerName: 'X-CSRF-TOKEN' }))
        calls.push(`${options?.method} ${url}`)
        if (options?.method === 'POST') { posts++; return new Response(JSON.stringify(sent), { status: 201 }) }
        return new Response('[]')
      }
      let draft = 'Synthetic reply'
      const result = await submitReply(draft, { current: false }, new AbortController().signal, {
        send: (content: string) => api.sendReply(42, content),
        confirmed: () => { draft = ''; calls.push('confirmed') },
        refresh: async () => { await Promise.all([api.getInboxMessages(42), api.getProfessionalInbox()]) },
      })
      assert.equal(result.status, 'sent')
      assert.equal(draft, '')
      assert.equal(posts, 1)
      assert.ok(calls[0].startsWith('POST '))
      assert.equal(calls[1], 'confirmed')
      assert.ok(calls[2].endsWith('/api/messages/inbox/42'))
      assert.ok(calls[3].endsWith('/api/messages/inbox'))
    })
    await context.test('patient follow-up posts content and recipient only, then refreshes patient history and list', async () => {
      const calls: string[] = []
      const controller = new AbortController()
      globalThis.fetch = async (url, options) => {
        assert.equal(options?.credentials, 'include')
        assert.equal(options?.signal, controller.signal)
        if (String(url).endsWith('/csrf')) return new Response(JSON.stringify({ token: 'test', headerName: 'X-CSRF-TOKEN' }))
        calls.push(`${options?.method} ${url}`)
        if (options?.method === 'POST') {
          assert.ok(String(url).endsWith('/api/messages'))
          assert.deepEqual(JSON.parse(String(options?.body)), { recipientId: 9, content: 'patient follow-up' })
          assert.equal((options?.headers as Record<string, string>)['X-CSRF-TOKEN'], 'test')
          return new Response(JSON.stringify(sent), { status: 201 })
        }
        return new Response('[]')
      }
      let draft = 'patient follow-up'
      const result = await submitReply(draft, { current: false }, controller.signal, {
        send: (content: string) => api.sendMessage({ recipientId: 9, content }, { signal: controller.signal }),
        confirmed: () => { draft = '' },
        refresh: async () => { await Promise.all([
          api.getConversationMessages(9, { signal: controller.signal }), api.getConversations({ signal: controller.signal }),
        ]) },
      })
      assert.equal(result.status, 'sent')
      assert.equal(draft, '')
      assert.equal(calls.length, 3)
      assert.ok(calls[0].startsWith('POST '))
      assert.ok(calls[1].endsWith('/api/messages/conversations/9'))
      assert.ok(calls[2].endsWith('/api/messages/conversations'))
      const html = renderPatientReply()
      assert.match(html, /Reply to this professional/)
      assert.match(html, /name="content"/)
      assert.doesNotMatch(html, /name="senderId"|name="recipientId"/)
    })

    await context.test('nonblank and Unicode boundaries validate before sending', async () => {
      let calls = 0
      const deps = { send: async () => { calls++ }, confirmed: () => {}, refresh: async () => {} }
      for (const content of ['', ' \t\n', '😀'.repeat(5001)]) {
        assert.equal((await submitReply(content, { current: false }, new AbortController().signal, deps)).status, 'error')
      }
      assert.equal(calls, 0)
      assert.equal((await submitReply('😀'.repeat(5000), { current: false }, new AbortController().signal, deps)).status, 'sent')
      assert.equal(calls, 1)
    })
    await context.test('duplicate submit stays blocked through refresh', async () => {
      let release!: () => void
      let calls = 0
      const guard = { current: false }
      const signal = new AbortController().signal
      const deps = { send: async () => { calls++ }, confirmed: () => {}, refresh: () => new Promise<void>((done) => { release = done }) }
      const first = submitReply('reply', guard, signal, deps)
      await Promise.resolve()
      assert.equal(guard.current, true)
      assert.equal((await submitReply('reply', guard, signal, deps)).status, 'ignored')
      release()
      assert.equal((await first).status, 'sent')
      assert.equal(calls, 1)
      assert.equal(guard.current, false)
    })
    await context.test('errors preserve draft without retry; refresh failure remains a sent reply', async () => {
      for (const failure of [new TypeError('network'), ...[400, 401, 403, 404, 500].map((status) => new ApiError(status, '', ''))]) {
        let draft = 'draft'
        let calls = 0
        const result = await submitReply(draft, { current: false }, new AbortController().signal, {
          send: async () => { calls++; throw failure }, confirmed: () => { draft = '' }, refresh: async () => assert.fail('must not refresh'),
        })
        assert.equal(result.status, 'error')
        assert.equal(draft, 'draft')
        assert.equal(calls, 1)
      }
      let draft = 'draft'
      const result = await submitReply(draft, { current: false }, new AbortController().signal, {
        send: async () => sent, confirmed: () => { draft = '' }, refresh: async () => { throw new Error('refresh') },
      })
      assert.equal(result.status, 'refreshError')
      assert.match(result.message, /reply was sent/)
      assert.equal(draft, '')
    })
    await context.test('switch/unmount ignores late success/failure and never refreshes wrong patient', async () => {
      for (const reject of [false, true]) {
        let finish!: () => void
        const controller = new AbortController()
        let confirmed = 0
        const pending = submitReply('old patient draft', { current: false }, controller.signal, {
          send: () => new Promise((resolve, fail) => { finish = () => reject ? fail(new Error('old failure')) : resolve(sent) }),
          confirmed: () => { confirmed++ }, refresh: async () => assert.fail('stale refresh'),
        })
        controller.abort()
        finish()
        assert.equal((await pending).status, 'ignored')
        assert.equal(confirmed, 0)
      }
      const controller = new AbortController()
      controller.abort()
      assert.equal((await submitReply('draft', { current: false }, controller.signal, {
        send: async () => assert.fail('cancelled submit'), confirmed: () => {}, refresh: async () => {},
      })).status, 'ignored')
    })
    await context.test('accessible reply form and existing role guards', () => {
      const html = renderReply()
      assert.match(html, /Reply to this patient/)
      assert.match(html, /<label[^>]*for="reply-content"/)
      assert.match(html, /name="content"/)
      assert.match(html, /role="alert"/)
      assert.doesNotMatch(html, /name="senderId"|name="recipientId"/)
      const base = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {}, acceptSession: () => {} }
      for (const role of ['DOCTOR', 'STAFF']) {
        const state = { ...base, user: { id: 7, email: 'synthetic@example.test', role } }
        assert.match(renderRoute(state, `/${role.toLowerCase()}/inbox/42`), /Loading patient inbox/)
        assert.match(renderRoute({ ...state, user: { ...state.user, role: 'PATIENT' } }, `/${role.toLowerCase()}/inbox/42`), /accounts only/)
      }
    })
  } finally { globalThis.fetch = original; await server.close() }
})
