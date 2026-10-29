import { describe, expect, it, vi } from 'vitest'

import type { Session } from './contracts'
import { createSessionApi } from './session'

const session: Session = {
  userId: '20ba43da-901e-44d7-bfb8-c7d3e10c0e81' as Session['userId'],
  username: 'learner',
  csrfToken: 'csrf-value',
  expiresAt: '2026-10-30T08:00:00Z',
}

function response(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

describe('session API', () => {
  it('treats an unauthenticated current-session response as an empty session', async () => {
    const request = vi.fn().mockResolvedValue(response(401))

    await expect(createSessionApi(request).current()).resolves.toBeNull()
  })

  it('returns typed API errors without leaking request details', async () => {
    const request = vi.fn().mockResolvedValue(
      response(429, {
        code: 'RATE_LIMITED',
        message: 'Too many requests. Try again later.',
        retryable: true,
        requestId: 'request-2',
      }),
    )

    const result = createSessionApi(request).login({ username: 'learner', password: 'secret' })

    await expect(result).rejects.toMatchObject({
      name: 'ApiClientError',
      status: 429,
      code: 'RATE_LIMITED',
      retryable: true,
    })
  })

  it('sends the CSRF token when logging out', async () => {
    const request = vi.fn().mockResolvedValue(response(204))

    await createSessionApi(request).logout(session.csrfToken)

    expect(request).toHaveBeenCalledWith(
      '/api/v1/sessions/current',
      expect.objectContaining({ headers: { 'X-CSRF-Token': 'csrf-value' } }),
    )
  })
})
