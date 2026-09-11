import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContextProvider } from '../auth/AuthContext'
import { createFakeAuthProvider } from '../test/fakeAuthProvider'
import { ReportsPage } from './ReportsPage'

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

const KPIS_RESPONSE = {
  range: 'last24h',
  generatedAt: '2026-09-11T15:00:00Z',
  reservasPorHora: {
    bucketCount: 2,
    buckets: [
      { hourStart: '2026-09-11T14:00:00Z', count: 0 },
      { hourStart: '2026-09-11T15:00:00Z', count: 2 },
    ],
  },
  tiempoDeCiclo: { unit: 'seconds', averageSeconds: 7200.0, completedBookingsCount: 2 },
  equiposOcupados: { asOf: '2026-09-11T15:00:00Z', count: 1, resourceIds: ['5f9a1111-2222-3333-4444-555566667777'] },
}

const TOP_RESOURCES_RESPONSE = {
  range: 'last7d',
  generatedAt: '2026-09-11T15:00:00Z',
  resources: [
    { resourceId: '5f9a1111-2222-3333-4444-555566667777', approvedCount: 7 },
    { resourceId: '9c1b1111-2222-3333-4444-555566667777', approvedCount: 3 },
  ],
}

function renderReportsPage() {
  return render(
    <AuthContextProvider provider={createFakeAuthProvider({ authenticated: true, accessToken: 'token' })}>
      <ReportsPage />
    </AuthContextProvider>,
  )
}

function mockFetchFor(roles: string[]) {
  return vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input)
    if (url.endsWith('/api/me')) {
      return meResponse(roles)
    }
    if (url.includes('/api/report/kpis')) {
      return new Response(JSON.stringify(KPIS_RESPONSE), { status: 200, headers: { 'Content-Type': 'application/json' } })
    }
    if (url.includes('/api/report/top-resources')) {
      return new Response(JSON.stringify(TOP_RESOURCES_RESPONSE), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    throw new Error(`unexpected fetch: ${url}`)
  })
}

describe('ReportsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders the three KPI panels and the top-resources list for admin.test', async () => {
    const fetchMock = mockFetchFor(['ADMIN'])
    vi.stubGlobal('fetch', fetchMock)

    renderReportsPage()

    const reservas = await screen.findByTestId('reservas-por-hora-panel')
    expect(within(reservas).getByText('2 buckets')).toBeInTheDocument()

    const ciclo = screen.getByTestId('tiempo-de-ciclo-panel')
    expect(within(ciclo).getByText(/7200/)).toBeInTheDocument()
    expect(within(ciclo).getByText('Reservas completadas: 2')).toBeInTheDocument()

    const ocupados = screen.getByTestId('equipos-ocupados-panel')
    expect(within(ocupados).getByText('1 recursos ocupados')).toBeInTheDocument()

    const topResources = screen.getByTestId('top-resources-list')
    expect(within(topResources).getByTestId('top-resource-5f9a1111-2222-3333-4444-555566667777')).toHaveTextContent('7')
    expect(within(topResources).getByTestId('top-resource-9c1b1111-2222-3333-4444-555566667777')).toHaveTextContent('3')
  })

  it('omits the range param on the initial fetch so each endpoint applies its own documented default', async () => {
    const fetchMock = mockFetchFor(['ADMIN'])
    vi.stubGlobal('fetch', fetchMock)

    renderReportsPage()

    await screen.findByTestId('reservas-por-hora-panel')

    const kpisCalls = fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/report/kpis'))
    const topResourcesCalls = fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/report/top-resources'))
    expect(kpisCalls).toHaveLength(1)
    expect(topResourcesCalls).toHaveLength(1)
    expect(String(kpisCalls[0][0])).not.toContain('range=')
    expect(String(topResourcesCalls[0][0])).not.toContain('range=')
    expect(String(topResourcesCalls[0][0])).not.toContain('last24h')
  })

  it('re-fetches both endpoints with the selected range', async () => {
    const fetchMock = mockFetchFor(['ADMIN'])
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderReportsPage()

    await screen.findByTestId('reservas-por-hora-panel')
    await user.selectOptions(screen.getByLabelText('Rango'), 'last30d')

    await screen.findByTestId('reservas-por-hora-panel')
    const kpisCalls = fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/report/kpis'))
    const topResourcesCalls = fetchMock.mock.calls.filter(([input]) => String(input).includes('/api/report/top-resources'))
    expect(kpisCalls.some(([input]) => String(input).includes('range=last30d'))).toBe(true)
    expect(topResourcesCalls.some(([input]) => String(input).includes('range=last30d'))).toBe(true)
  })

  it('shows an access-denied message for tecnico.test and never calls GET /api/report/*', async () => {
    const fetchMock = mockFetchFor(['TECNICO'])
    vi.stubGlobal('fetch', fetchMock)

    renderReportsPage()

    expect(await screen.findByTestId('reports-access-denied')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/report/'))).toBe(false)
  })

  it('shows an access-denied message for estudiante.test and never calls GET /api/report/*', async () => {
    const fetchMock = mockFetchFor(['ESTUDIANTE'])
    vi.stubGlobal('fetch', fetchMock)

    renderReportsPage()

    expect(await screen.findByTestId('reports-access-denied')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/report/'))).toBe(false)
  })

  it('shows an access-denied message for auditor.test (report is Admin-only, unlike audit) and never calls GET /api/report/*', async () => {
    const fetchMock = mockFetchFor(['AUDITOR'])
    vi.stubGlobal('fetch', fetchMock)

    renderReportsPage()

    expect(await screen.findByTestId('reports-access-denied')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/report/'))).toBe(false)
  })

  it('surfaces a 400 from the server as an inline message, not a crash', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/me')) {
        return meResponse(['ADMIN'])
      }
      if (url.includes('/api/report/kpis')) {
        return new Response(JSON.stringify({ detail: "'range' must be one of [last24h, last7d, last30d], got: bogus" }), {
          status: 400,
          headers: { 'Content-Type': 'application/problem+json' },
        })
      }
      if (url.includes('/api/report/top-resources')) {
        return new Response(JSON.stringify(TOP_RESOURCES_RESPONSE), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderReportsPage()

    expect(await screen.findByText(/must be one of/)).toBeInTheDocument()
  })
})
