import { renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useMe } from './useMe'

describe('useMe', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('resolves the identity fetched from GET /api/me', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            sub: 'admin-uuid',
            username: 'admin.test',
            email: 'admin.test@campuslab.local',
            roles: ['ADMIN'],
            issuer: 'http://localhost:8081/realms/campuslab',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    const { result } = renderHook(() => useMe())

    expect(result.current.isLoading).toBe(true)
    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.me?.username).toBe('admin.test')
    expect(result.current.me?.roles).toEqual(['ADMIN'])
    expect(result.current.error).toBeNull()
  })

  it('surfaces an error and stops loading when GET /api/me fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))

    const { result } = renderHook(() => useMe())

    await waitFor(() => expect(result.current.isLoading).toBe(false))
    expect(result.current.me).toBeNull()
    expect(result.current.error).toMatch(/GET \/api\/me failed with status 401/)
  })
})
