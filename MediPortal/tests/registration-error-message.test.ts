import assert from 'node:assert/strict'
import test from 'node:test'
import { createServer } from 'vite'

test('registration error messages', async (context) => {
  const server = await createServer({ server: { middlewareMode: true, hmr: false }, appType: 'custom' })
  try {
    const { registrationErrorMessage } = await server.ssrLoadModule('/src/features/auth/registrationErrorMessage.ts')
    const { ApiError } = await server.ssrLoadModule('/src/services/apiClient.ts')

    await context.test('maps HTTP 400 to invalid registration feedback', () => {
      assert.equal(registrationErrorMessage(new ApiError(400, 'Bad Request', '/api/auth/register')),
        'Registration data was rejected. Check your name, personnummer, email, phone number, password, and account type.')
    })

    await context.test('maps HTTP 409 to duplicate email feedback', () => {
      assert.equal(registrationErrorMessage(new ApiError(409, 'Conflict', '/api/auth/register')),
        'This email address is already registered.')
      assert.equal(registrationErrorMessage(new ApiError(409, 'Conflict', '/api/auth/register', 'OTHER')),
        'This email address is already registered.')
    })

    await context.test('maps a taken personnummer to its own feedback without repeating it', () => {
      const message = registrationErrorMessage(new ApiError(409, 'Conflict', '/api/auth/register', 'PERSONNUMMER_TAKEN'))
      assert.equal(message, 'An account with this personnummer already exists.')
      assert.doesNotMatch(message, /\d/)
    })

    await context.test('maps other HTTP errors to generic registration feedback', () => {
      for (const status of [401, 403, 404, 500, 503]) {
        assert.equal(registrationErrorMessage(new ApiError(status, 'Internal details', '/api/auth/register')),
          'Registration failed. Please try again later.')
      }
    })

    await context.test('maps network and other non-API failures to connection feedback', () => {
      for (const error of [new TypeError('Failed to fetch'), new Error('Failure'), null, undefined]) {
        assert.equal(registrationErrorMessage(error),
          'Unable to reach the server. Check your connection and try again.')
      }
    })
  } finally {
    await server.close()
  }
})
