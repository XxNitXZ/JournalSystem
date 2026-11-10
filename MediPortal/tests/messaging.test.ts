import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('message composer API, submission and navigation', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const original = globalThis.fetch
  try {
    const api = await server.ssrLoadModule('/src/features/messaging/messageService.ts')
    const form = await server.ssrLoadModule('/src/features/messaging/messageForm.ts')
    const { ApiError } = await server.ssrLoadModule('/src/services/apiClient.ts')
    const { renderRoute } = await server.ssrLoadModule('/tests/auth-navigation-render.tsx')
    const { renderComposerForm } = await server.ssrLoadModule('/tests/messaging-render.tsx')
    const recipients = [{ id: 2, displayName: 'Synthetic Doctor', role: 'DOCTOR' }, { id: 3, displayName: 'Synthetic Staff', role: 'STAFF' }]
    const sent = { id: 10, createdAt: '2026-01-01T00:00:00Z' }
    await context.test('directory GET includes cookies and abort signal', async () => {
      const controller = new AbortController()
      globalThis.fetch = async (url, options) => {
        assert.ok(String(url).endsWith('/api/messages/recipients'))
        assert.equal(options?.method, 'GET')
        assert.equal(options?.credentials, 'include')
        assert.equal(options?.signal, controller.signal)
        return new Response(JSON.stringify(recipients))
      }
      assert.deepEqual(await api.getMessageRecipients({ signal: controller.signal }), recipients)
    })
    await context.test('POST sends only recipient/content and CSRF with cookies', async () => {
      let posts = 0
      globalThis.fetch = async (_url, options) => {
        assert.equal(options?.credentials, 'include')
        if (options?.method === 'GET') return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'synthetic' }))
        posts++
        assert.equal((options?.headers as Record<string, string>)['X-CSRF-TOKEN'], 'synthetic')
        assert.deepEqual(JSON.parse(String(options?.body)), { recipientId: 2, content: ' unchanged ' })
        return new Response(JSON.stringify(sent), { status: 201 })
      }
      assert.deepEqual(await api.sendMessage({ recipientId: 2, content: ' unchanged ', senderId: 999 }), sent)
      assert.equal(posts, 1)
    })
    await context.test('validation uses known recipients, nonblank text and code points', () => {
      for (const id of ['', '99', '2.0', '-1']) assert.match(form.validateMessage(id, 'text', recipients), /recipient/)
      for (const value of ['', ' \t\n']) assert.match(form.validateMessage('2', value, recipients), /Enter a message/)
      assert.equal(form.validateMessage('2', '😀'.repeat(5000), recipients), null)
      assert.match(form.validateMessage('2', '😀'.repeat(5001), recipients), /5,000/)
    })
    await context.test('success and pending guard prevent duplicate requests', async () => {
      let resolve!: (value: unknown) => void
      let calls = 0
      const guard = { current: false }
      const send = async (request: unknown) => {
        calls++
        assert.deepEqual(request, { recipientId: 2, content: ' draft ' })
        return new Promise((done) => { resolve = done })
      }
      const first = form.submitMessage('2', ' draft ', recipients, guard, send)
      assert.equal(guard.current, true)
      assert.deepEqual(await form.submitMessage('2', ' draft ', recipients, guard, send), { status: 'pending' })
      resolve(sent)
      assert.deepEqual(await first, { status: 'sent' })
      assert.equal(guard.current, false)
      assert.equal((await form.submitMessage('', 'draft', recipients, guard, send)).status, 'error')
      assert.equal(calls, 1)
    })
    await context.test('HTTP and network failures return feedback without POST retries', async () => {
      for (const status of [400, 401, 403, 404, 500]) {
        let posts = 0
        globalThis.fetch = async (_url, options) => {
          if (options?.method === 'GET') return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'synthetic' }))
          posts++
          return new Response('', { status })
        }
        const guard = { current: false }
        const result = await form.submitMessage('2', 'draft', recipients, guard, api.sendMessage)
        assert.equal(result.status, 'error')
        assert.equal(posts, 1)
        assert.equal(guard.current, false)
      }
      assert.match(form.messageErrorMessage(new ApiError(400, '', '')), /Check/)
      assert.match(form.messageErrorMessage(new ApiError(401, '', '')), /sign in/)
      assert.match(form.messageErrorMessage(new ApiError(403, '', '')), /cannot perform/)
      assert.match(form.messageErrorMessage(new ApiError(404, '', '')), /recipient/)
      const draft = { content: 'Synthetic draft' }
      const result = await form.submitMessage('2', draft.content, recipients, { current: false }, async () => { throw new TypeError('network') })
      assert.match(result.message, /Unable to confirm delivery/)
      assert.equal(draft.content, 'Synthetic draft')
      assert.match(form.recipientLoadError(new Error()), /Recipients could not be loaded/)
      assert.match(form.recipientLoadError(new ApiError(401, '', '')), /session/)
      globalThis.fetch = async () => { throw new TypeError('network') }
      await assert.rejects(api.getMessageRecipients(), TypeError)
    })
    await context.test('empty directory and form labels render accessibly', () => {
      const html = renderComposerForm(recipients)
      assert.match(html, /Synthetic Doctor — Doctor/)
      assert.match(html, /Synthetic Staff — Staff/)
      assert.match(html, /<textarea/)
      assert.match(html, /role="alert"/)
      assert.match(renderComposerForm([]), /No message recipients are available/)
      assert.doesNotMatch(renderComposerForm([]), /<form/)
    })
    await context.test('only patient sees composer and navigation; unknown sessions are blocked', () => {
      const base = { user: null, loading: false, error: '', signIn: async () => {}, signOut: async () => {}, acceptSession: () => {} }
      const patient = { ...base, user: { id: 1, email: 'synthetic@example.test', role: 'PATIENT' } }
      const path = '/patient/messages/new'
      assert.match(renderRoute(patient, path), /Loading recipients/)
      assert.match(renderRoute(patient, '/patient'), /href="\/patient\/messages\/new"/)
      for (const role of ['DOCTOR', 'STAFF']) {
        const state = { ...patient, user: { ...patient.user, role } }
        assert.match(renderRoute(state, path), /available to patient accounts only/)
        assert.doesNotMatch(renderRoute(state, `/${role.toLowerCase()}`), /href="\/patient\/messages\/new"/)
      }
      for (const state of [base, { ...patient, loading: true }, { ...patient, error: 'Session unavailable' }]) {
        assert.doesNotMatch(renderRoute(state, path), /Loading recipients|New message/)
      }
    })
  } finally { globalThis.fetch = original; await server.close() }
})
