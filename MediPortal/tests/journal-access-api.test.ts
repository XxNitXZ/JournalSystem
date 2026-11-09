import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('journal access and own record API calls', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  const originalFetch = globalThis.fetch
  try {
    const service = await server.ssrLoadModule('/src/features/journalaccess/journalAccessService.ts')
    const { getOwnPatientRecord } = await server.ssrLoadModule('/src/features/patientmanagement/patientService.ts')
    const { ApiError, API_BASE_URL } = await server.ssrLoadModule('/src/services/apiClient.ts')

    function recordCalls(body: unknown) {
      const calls: { method: string; url: string; headers: Record<string, string>; body?: string }[] = []
      globalThis.fetch = async (url, options) => {
        const call = { method: String(options?.method), url: String(url), headers: options?.headers as Record<string, string>,
          body: options?.body as string | undefined }
        calls.push(call)
        assert.equal(options?.credentials, 'include')
        if (call.url.endsWith('/api/auth/csrf')) {
          return new Response(JSON.stringify({ token: 'csrf-token', headerName: 'X-XSRF-TOKEN' }))
        }
        return new Response(JSON.stringify(body))
      }
      return calls
    }

    await context.test('reads statuses, lists and details with GET only', async () => {
      let calls = recordCalls({ status: 'PENDING' })
      assert.equal(await service.getMyJournalAccessStatus(), 'PENDING')
      assert.deepEqual(calls.map((call) => `${call.method} ${call.url}`), [`GET ${API_BASE_URL}/api/journal-access/me`])

      calls = recordCalls({ pending: 3 })
      assert.equal(await service.getPendingJournalAccessCount(), 3)
      assert.deepEqual(calls.map((call) => `${call.method} ${call.url}`),
        [`GET ${API_BASE_URL}/api/journal-access-requests/pending/count`])

      calls = recordCalls([])
      assert.deepEqual(await service.listJournalAccessRequests(), [])
      assert.deepEqual(calls.map((call) => `${call.method} ${call.url}`), [
        `GET ${API_BASE_URL}/api/journal-access-requests`,
      ])

      calls = recordCalls({ id: 5 })
      await service.getJournalAccessRequest(5)
      assert.deepEqual(calls.map((call) => `${call.method} ${call.url}`), [`GET ${API_BASE_URL}/api/journal-access-requests/5`])
    })

    await context.test('asking for access is a POST with a CSRF token and no personal data', async () => {
      const calls = recordCalls({ status: 'AWAITING_RECORD' })
      assert.equal(await service.requestJournalAccess(), 'AWAITING_RECORD')
      assert.deepEqual(calls.map((call) => `${call.method} ${call.url}`), [
        `GET ${API_BASE_URL}/api/auth/csrf`,
        `POST ${API_BASE_URL}/api/journal-access/me/request`,
      ])
      assert.equal(calls[1].headers['X-XSRF-TOKEN'], 'csrf-token')
      assert.equal(calls[1].body, '{}')
    })

    for (const decision of ['approve', 'reject', 'reopen']) {
      await context.test(`${decision} is a POST with a CSRF token`, async () => {
        const calls = recordCalls({ id: 5, status: 'APPROVED' })
        await service.decideJournalAccessRequest(5, decision)
        assert.deepEqual(calls.map((call) => `${call.method} ${call.url}`), [
          `GET ${API_BASE_URL}/api/auth/csrf`,
          `POST ${API_BASE_URL}/api/journal-access-requests/5/${decision}`,
        ])
        assert.equal(calls[1].headers['X-XSRF-TOKEN'], 'csrf-token')
      })
    }

    await context.test('the own record is always /api/patients/me, without any ID', async () => {
      const calls = recordCalls({ id: 'syn-1', email: 'ali@example.test' })
      await getOwnPatientRecord()
      assert.deepEqual(calls.map((call) => `${call.method} ${call.url}`), [`GET ${API_BASE_URL}/api/patients/me`])
    })

    await context.test('errors keep their status and code', async () => {
      globalThis.fetch = async (url) => String(url).endsWith('/api/auth/csrf')
        ? new Response(JSON.stringify({ token: 't', headerName: 'X-XSRF-TOKEN' }))
        : new Response(JSON.stringify({ code: 'PERSONNUMMER_MISMATCH' }), { status: 409 })
      await assert.rejects(service.decideJournalAccessRequest(5, 'approve'), (error: unknown) =>
        error instanceof ApiError && error.status === 409 && error.code === 'PERSONNUMMER_MISMATCH')
      globalThis.fetch = async () => new Response(JSON.stringify({ code: 'RECORD_NOT_LINKED' }), { status: 404 })
      await assert.rejects(getOwnPatientRecord(), (error: unknown) =>
        error instanceof ApiError && error.status === 404 && error.code === 'RECORD_NOT_LINKED')
    })
  } finally {
    globalThis.fetch = originalFetch
    await server.close()
  }
})
