import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('patient conversations API, rendering and access', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const original = globalThis.fetch
  try {
    const api = await server.ssrLoadModule('/src/features/messaging/messageService.ts')
    const { ApiError } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const { conversationError } = await server.ssrLoadModule('/src/features/messaging/conversationText.ts')
    const { renderList, renderHistory } = await server.ssrLoadModule('/tests/conversations-render.tsx')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const conversations = [{ recipientId: 9, displayName: 'Synthetic Doctor', role: 'DOCTOR', messageCount: 2, lastMessageAt: '2026-01-01T00:00:00Z' }]
    await context.test('GET list and selected history with session cookies, signal, no writes or CSRF', async () => {
      const calls: string[] = []
      const controller = new AbortController()
      globalThis.fetch = async (url, options) => {
        calls.push(`${options?.method} ${url}`)
        assert.equal(options?.credentials, 'include')
        assert.equal(options?.signal, controller.signal)
        return new Response(JSON.stringify(String(url).endsWith('/9') ? [] : conversations))
      }
      assert.deepEqual(await api.getConversations({ signal: controller.signal }), conversations)
      assert.deepEqual(await api.getConversationMessages(9, { signal: controller.signal }), [])
      assert.equal(calls.length, 2)
      assert.ok(calls[0].endsWith('/api/messages/conversations'))
      assert.ok(calls[1].endsWith('/api/messages/conversations/9'))
      assert.ok(calls.every((value) => value.startsWith('GET ')))
    })
    await context.test('aborted selected thread requests propagate cancellation', async () => {
      const controller = new AbortController()
      globalThis.fetch = async (_url, options) => new Promise((_resolve, reject) => {
        options?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
      })
      const pending = api.getConversationMessages(9, { signal: controller.signal })
      controller.abort()
      await assert.rejects(pending, { name: 'AbortError' })
    })
    await context.test('list renders professional metadata and selected link, including empty state', () => {
      const html = renderList(conversations, 9)
      assert.match(html, /Synthetic Doctor/)
      assert.match(html, /Doctor/)
      assert.match(html, /2 messages/)
      assert.match(html, /Latest:/)
      assert.match(html, /aria-current="page"/)
      assert.match(html, /href="\/patient\/messages\/9"/)
      assert.match(renderList([]), /no conversations yet/)
    })
    await context.test('history preserves server order and microsecond precision without mutation, identifies patient and escapes content', () => {
      const items = [
        { id: 3, senderId: 42, recipientId: 9, content: '<script>alert(1)</script>\nSynthetic draft', createdAt: '2026-01-01T00:00:00.000001Z' },
        { id: 1, senderId: 9, recipientId: 42, content: 'Equal-time reply', createdAt: '2026-01-01T00:00:00.000002Z' },
        { id: 2, senderId: 9, recipientId: 42, content: 'Latest reply', createdAt: '2026-01-02T00:00:00Z' },
      ]
      const originalItems = structuredClone(items)
      items.forEach(Object.freeze)
      Object.freeze(items)
      const html = renderHistory(items, 42)
      assert.ok(html.indexOf('Synthetic draft') < html.indexOf('Equal-time reply'))
      assert.ok(html.indexOf('Equal-time reply') < html.indexOf('Latest reply'))
      assert.match(html, /<strong>You<\/strong>/)
      assert.match(html, /<strong>Synthetic Doctor<\/strong>/)
      assert.match(html, /Sent/)
      assert.match(html, /Reply/)
      assert.match(html, /&lt;script&gt;/)
      assert.doesNotMatch(html, /<script>/)
      assert.deepEqual(items, originalItems)
      assert.match(renderHistory([], 42), /no messages in this conversation/)
      assert.doesNotMatch(renderHistory([items[2]], 99), /<strong>You<\/strong>/)
    })
    await context.test('HTTP and network errors have clear feedback', async () => {
      for (const status of [400, 401, 403, 404, 500]) {
        globalThis.fetch = async () => new Response('', { status })
        await assert.rejects(api.getConversations(), (error: unknown) => error instanceof ApiError && error.status === status)
        assert.ok(conversationError(new ApiError(status, '', '')).length > 0)
      }
      assert.match(conversationError(new ApiError(401, '', '')), /sign in/)
      assert.match(conversationError(new ApiError(403, '', '')), /access/)
      assert.match(conversationError(new ApiError(404, '', '')), /not available/)
      globalThis.fetch = async () => { throw new TypeError('network') }
      await assert.rejects(api.getConversationMessages(9), TypeError)
      assert.match(conversationError(new TypeError()), /could not be loaded/)
    })
    await context.test('patient routes and composer links; other roles and unknown sessions blocked', () => {
      const base = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {}, acceptSession: () => {} }
      const patient = { ...base, user: { id: 42, email: 'synthetic@example.test', role: 'PATIENT' } }
      for (const path of ['/patient/messages', '/patient/messages/9']) {
        assert.match(renderRoute(patient, path), /Loading conversations/)
        for (const role of ['DOCTOR', 'STAFF']) assert.match(renderRoute({ ...patient, user: { ...patient.user, role } }, path), /available to patient accounts only/)
        for (const state of [base, { ...patient, loading: true }, { ...patient, error: 'Session unavailable' }]) {
          assert.doesNotMatch(renderRoute(state, path), /Loading conversations|Your conversations/)
        }
      }
      assert.match(renderRoute(patient, '/patient'), /href="\/patient\/messages"/)
      assert.match(renderRoute(patient, '/patient/messages/new'), /View your conversations/)
      assert.match(renderRoute(patient, '/patient/messages/new'), /Loading recipients/)
    })
  } finally { globalThis.fetch = original; await server.close() }
})
