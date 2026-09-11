import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContextProvider } from '../auth/AuthContext'
import { createFakeAuthProvider } from '../test/fakeAuthProvider'
import { AuditPage } from './AuditPage'

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

const TIMELINE_EVENT = {
  id: 'b3f1c111-2222-3333-4444-555566667777',
  eventId: '3fa1c111-2222-3333-4444-555566667777',
  bookingId: 'b1c2c111-2222-3333-4444-555566667777',
  resourceId: '5f9ac111-2222-3333-4444-555566667777',
  eventType: 'BOOKING_APROBADA',
  actorOid: 'technician-oid',
  actorRoles: ['TECNICO'],
  studentOid: 'student-oid',
  fromStatus: 'SOLICITADA',
  toStatus: 'APROBADA',
  traceId: '8b2cc111-2222-3333-4444-555566667777',
  occurredAt: '2026-09-10T12:00:00Z',
  receivedAt: '2026-09-10T12:00:01Z',
}

function renderAuditPage() {
  return render(
    <AuthContextProvider provider={createFakeAuthProvider({ authenticated: true, accessToken: 'token' })}>
      <AuditPage />
    </AuthContextProvider>,
  )
}

describe('AuditPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('lets admin.test search and renders the returned timeline', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/me')) {
        return meResponse(['ADMIN'])
      }
      if (url.includes('/api/audit/timeline')) {
        return new Response(JSON.stringify([TIMELINE_EVENT]), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderAuditPage()

    const form = await screen.findByRole('form', { name: 'Filtrar timeline' })
    await user.click(within(form).getByRole('button', { name: 'Buscar' }))

    const list = await screen.findByTestId('audit-timeline-list')
    expect(await within(list).findByText('BOOKING_APROBADA')).toBeInTheDocument()
    expect(within(list).getByText(/technician-oid/)).toBeInTheDocument()
  })

  it('sends the eventType filter as a query parameter', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/me')) {
        return meResponse(['AUDITOR'])
      }
      if (url.includes('/api/audit/timeline')) {
        return new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } })
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderAuditPage()

    const form = await screen.findByRole('form', { name: 'Filtrar timeline' })
    await user.selectOptions(within(form).getByLabelText('Tipo de evento'), 'BOOKING_APROBADA')
    await user.click(within(form).getByRole('button', { name: 'Buscar' }))

    await screen.findByText('No hay eventos para estos filtros.')
    const timelineCall = fetchMock.mock.calls.find(([input]) => String(input).includes('/api/audit/timeline'))
    expect(timelineCall).toBeDefined()
    expect(String(timelineCall?.[0])).toContain('eventType=BOOKING_APROBADA')
  })

  it('shows an access-denied message for tecnico.test and never calls GET /api/audit/timeline', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/me')) {
        return meResponse(['TECNICO'])
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderAuditPage()

    expect(await screen.findByTestId('audit-access-denied')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/audit/timeline'))).toBe(false)
  })

  it('shows an access-denied message for estudiante.test and never calls GET /api/audit/timeline', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/me')) {
        return meResponse(['ESTUDIANTE'])
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderAuditPage()

    expect(await screen.findByTestId('audit-access-denied')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).includes('/api/audit/timeline'))).toBe(false)
  })

  it('surfaces a 400 from the server as an inline message, not a crash', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/me')) {
        return meResponse(['ADMIN'])
      }
      if (url.includes('/api/audit/timeline')) {
        return new Response(JSON.stringify({ detail: "'eventType' is not a recognized value: BOGUS" }), {
          status: 400,
          headers: { 'Content-Type': 'application/problem+json' },
        })
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const user = userEvent.setup()
    renderAuditPage()

    const form = await screen.findByRole('form', { name: 'Filtrar timeline' })
    await user.click(within(form).getByRole('button', { name: 'Buscar' }))

    expect(await screen.findByText("'eventType' is not a recognized value: BOGUS")).toBeInTheDocument()
  })
})
