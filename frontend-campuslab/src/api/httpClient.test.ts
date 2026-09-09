import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { setActiveAuthProvider } from '../auth/authRegistry'
import { createFakeAuthProvider } from '../test/fakeAuthProvider'
import { apiFetch } from './httpClient'

describe('apiFetch', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 200 })))
  })

  afterEach(() => {
    setActiveAuthProvider(null)
    vi.unstubAllGlobals()
  })

  it('attaches a bearer header when a token is available', async () => {
    setActiveAuthProvider(createFakeAuthProvider({ authenticated: true, accessToken: 'live-token' }))

    await apiFetch('/api/me')

    const [, init] = vi.mocked(fetch).mock.calls[0]
    const headers = new Headers(init?.headers)
    expect(headers.get('Authorization')).toBe('Bearer live-token')
  })

  it('never attaches a stale/empty Authorization header before login', async () => {
    setActiveAuthProvider(null)

    await apiFetch('/api/me')

    const [, init] = vi.mocked(fetch).mock.calls[0]
    const headers = new Headers(init?.headers)
    expect(headers.has('Authorization')).toBe(false)
  })

  it('never attaches a stale/empty Authorization header after logout', async () => {
    setActiveAuthProvider(createFakeAuthProvider({ authenticated: false }))

    await apiFetch('/api/me')

    const [, init] = vi.mocked(fetch).mock.calls[0]
    const headers = new Headers(init?.headers)
    expect(headers.has('Authorization')).toBe(false)
  })
})
