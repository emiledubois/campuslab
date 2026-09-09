import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContextProvider } from '../auth/AuthContext'
import { createFakeAuthProvider } from '../test/fakeAuthProvider'
import { DashboardPage } from './DashboardPage'

describe('DashboardPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders identity fetched live from GET /api/me for the logged-in user', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            sub: 'estudiante-uuid',
            username: 'estudiante.test',
            email: 'estudiante.test@campuslab.local',
            roles: ['ESTUDIANTE'],
            issuer: 'http://localhost:8081/realms/campuslab',
          }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    )

    render(
      <AuthContextProvider provider={createFakeAuthProvider({ authenticated: true, accessToken: 'token' })}>
        <DashboardPage />
      </AuthContextProvider>,
    )

    expect(await screen.findByTestId('me-username')).toHaveTextContent('estudiante.test')
    expect(screen.getByTestId('me-roles')).toHaveTextContent('ESTUDIANTE')
  })

  it('shows an error rather than crashing when GET /api/me fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 401 })),
    )

    render(
      <AuthContextProvider provider={createFakeAuthProvider({ authenticated: true, accessToken: 'token' })}>
        <DashboardPage />
      </AuthContextProvider>,
    )

    expect(await screen.findByText(/GET \/api\/me failed with status 401/)).toBeInTheDocument()
  })
})
