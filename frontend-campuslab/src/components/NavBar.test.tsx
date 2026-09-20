import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContextProvider } from '../auth/AuthContext'
import { createFakeAuthProvider } from '../test/fakeAuthProvider'
import { NavBar } from './NavBar'

function meResponse(roles: string[]) {
  return new Response(
    JSON.stringify({
      sub: 'user-sub',
      oid: 'user-oid',
      username: 'user.test',
      email: 'user.test@campuslab.local',
      roles,
      issuer: 'https://login.microsoftonline.com/test-tenant/v2.0',
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

function renderNavBar(initialPath = '/dashboard') {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthContextProvider provider={createFakeAuthProvider({ authenticated: true, accessToken: 'token' })}>
        <NavBar />
      </AuthContextProvider>
    </MemoryRouter>,
  )
}

const ALL_LINK_TESTIDS = [
  'nav-link-dashboard',
  'nav-link-bookings',
  'nav-link-catalog',
  'nav-link-reports',
  'nav-link-audit',
]

function expectExactLinks(shown: string[]) {
  for (const testId of ALL_LINK_TESTIDS) {
    if (shown.includes(testId)) {
      expect(screen.getByTestId(testId)).toBeInTheDocument()
    } else {
      expect(screen.queryByTestId(testId)).not.toBeInTheDocument()
    }
  }
}

describe('NavBar', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows all five links for an ADMIN identity', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(meResponse(['ADMIN'])))

    renderNavBar()

    await screen.findByTestId('nav-link-reports')
    expectExactLinks(ALL_LINK_TESTIDS)
  })

  it('shows dashboard, bookings and catalog but not reports or audit for a TECNICO identity', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(meResponse(['TECNICO'])))

    renderNavBar()

    await screen.findByTestId('nav-link-catalog')
    expectExactLinks(['nav-link-dashboard', 'nav-link-bookings', 'nav-link-catalog'])
  })

  it('shows only dashboard and bookings for an ESTUDIANTE identity', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(meResponse(['ESTUDIANTE'])))

    renderNavBar()

    await screen.findByTestId('nav-link-bookings')
    expectExactLinks(['nav-link-dashboard', 'nav-link-bookings'])
  })

  it('shows only dashboard and audit for an AUDITOR identity', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(meResponse(['AUDITOR'])))

    renderNavBar()

    await screen.findByTestId('nav-link-audit')
    expectExactLinks(['nav-link-dashboard', 'nav-link-audit'])
  })

  it('shows no role-gated link while the identity is still loading, never a flash of the resolved role', async () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))

    renderNavBar()

    await screen.findByTestId('nav-link-dashboard')
    expectExactLinks(['nav-link-dashboard'])
  })

  it('marks the active route distinctly from the others via aria-current', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(meResponse(['ADMIN'])))

    renderNavBar('/bookings')

    const bookingsLink = await screen.findByTestId('nav-link-bookings')
    const catalogLink = screen.getByTestId('nav-link-catalog')
    expect(bookingsLink).toHaveAttribute('aria-current', 'page')
    expect(catalogLink).not.toHaveAttribute('aria-current')
  })

  it('renders the Cerrar sesion button and logs out when clicked', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(meResponse(['ADMIN'])))

    renderNavBar()

    expect(await screen.findByRole('button', { name: 'Cerrar sesion' })).toBeInTheDocument()
  })
})
