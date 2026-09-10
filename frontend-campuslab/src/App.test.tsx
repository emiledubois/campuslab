import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import { AuthContextProvider } from './auth/AuthContext'
import { createFakeAuthProvider } from './test/fakeAuthProvider'

function renderApp(initialPath: string, authenticated: boolean) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthContextProvider provider={createFakeAuthProvider({ authenticated })}>
        <App />
      </AuthContextProvider>
    </MemoryRouter>,
  )
}

describe('App', () => {
  it('always renders the CampusLab heading', async () => {
    renderApp('/login', false)

    expect(await screen.findByRole('heading', { name: 'CampusLab' })).toBeInTheDocument()
  })

  it('redirects an unauthenticated visit to /dashboard to /login without rendering dashboard content', async () => {
    renderApp('/dashboard', false)

    expect(await screen.findByText('Inicia sesion para continuar.')).toBeInTheDocument()
    expect(screen.queryByText('Dashboard')).not.toBeInTheDocument()
  })

  it('renders the dashboard for an authenticated visit to /dashboard', async () => {
    renderApp('/dashboard', true)

    expect(await screen.findByText('Dashboard')).toBeInTheDocument()
  })

  describe('login -> logout -> redirect transition', () => {
    beforeEach(() => {
      vi.stubGlobal(
        'fetch',
        vi.fn().mockResolvedValue(
          new Response(
            JSON.stringify({
              sub: 'estudiante-sub',
              oid: 'estudiante-oid',
              username: 'estudiante.test',
              email: 'estudiante.test@campuslab.local',
              roles: ['ESTUDIANTE'],
              issuer: 'https://login.microsoftonline.com/test-tenant/v2.0',
            }),
            { status: 200, headers: { 'Content-Type': 'application/json' } },
          ),
        ),
      )
    })

    afterEach(() => {
      vi.unstubAllGlobals()
    })

    it('shows the dashboard after login, then enforces login again after logout', async () => {
      const user = userEvent.setup()

      // Arrange: unauthenticated user arrives via the OIDC redirect callback, which
      // (per the fake provider) resolves login and lands on the dashboard.
      renderApp('/auth/callback', false)
      expect(await screen.findByText('Dashboard')).toBeInTheDocument()

      // Act: user logs out from the dashboard.
      await user.click(screen.getByRole('button', { name: 'Cerrar sesion' }))

      // Assert: session is cleared and the app requires login again, dashboard content gone.
      expect(await screen.findByText('Inicia sesion para continuar.')).toBeInTheDocument()
      expect(screen.queryByText('Dashboard')).not.toBeInTheDocument()
    })
  })
})
