import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContextProvider } from '../auth/AuthContext'
import { createFakeAuthProvider } from '../test/fakeAuthProvider'
import { BookingsPage } from './BookingsPage'

function meResponse(roles: string[]) {
  return new Response(
    JSON.stringify({
      sub: 'user-uuid',
      username: 'user.test',
      email: 'user.test@campuslab.local',
      roles,
      issuer: 'http://localhost:8081/realms/campuslab',
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

const OWN_BOOKING = {
  id: 'b1c2c3d4-2a3b-4e10-9c2f-8b6d2b6b0a11',
  resourceId: '5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a11',
  studentSub: 'estudiante-uuid',
  requestedStart: '2026-09-15T10:00:00Z',
  requestedEnd: '2026-09-15T12:00:00Z',
  notes: 'Practica de redes',
  status: 'SOLICITADA',
  version: 0,
  createdAt: '2026-09-01T12:00:00Z',
  updatedAt: '2026-09-01T12:00:00Z',
}

function renderBookingsPage() {
  return render(
    <AuthContextProvider provider={createFakeAuthProvider({ authenticated: true, accessToken: 'token' })}>
      <BookingsPage />
    </AuthContextProvider>,
  )
}

describe('BookingsPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders a create form and the scoped list for estudiante.test', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.endsWith('/api/me')) {
          return meResponse(['ESTUDIANTE'])
        }
        if (url.endsWith('/api/bookings')) {
          return new Response(JSON.stringify([OWN_BOOKING]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        throw new Error(`unexpected fetch: ${url}`)
      }),
    )

    renderBookingsPage()

    expect(await screen.findByText(OWN_BOOKING.resourceId)).toBeInTheDocument()
    expect(screen.getByRole('form', { name: 'Crear reserva' })).toBeInTheDocument()
  })

  it('appends a newly created booking to the list without a page reload', async () => {
    const created = { ...OWN_BOOKING, id: 'new-id', resourceId: 'new-resource-id' }
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        if (url.endsWith('/api/me')) {
          return meResponse(['ESTUDIANTE'])
        }
        if (url.endsWith('/api/bookings') && (!init || init.method === undefined)) {
          return new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } })
        }
        if (url.endsWith('/api/bookings') && init?.method === 'POST') {
          return new Response(JSON.stringify(created), {
            status: 201,
            headers: { 'Content-Type': 'application/json', Location: `/api/bookings/${created.id}` },
          })
        }
        throw new Error(`unexpected fetch: ${url} ${init?.method}`)
      }),
    )

    const user = userEvent.setup()
    renderBookingsPage()

    const form = await screen.findByRole('form', { name: 'Crear reserva' })
    await user.type(within(form).getByLabelText('Recurso (id)'), 'new-resource-id')
    await user.type(within(form).getByLabelText('Inicio'), '2026-09-15T10:00')
    await user.type(within(form).getByLabelText('Fin'), '2026-09-15T12:00')
    await user.click(within(form).getByRole('button', { name: 'Solicitar' }))

    expect(await screen.findByText('new-resource-id')).toBeInTheDocument()
  })

  it('renders the unscoped list with status-change controls but no create form for tecnico.test/admin.test', async () => {
    const other = {
      ...OWN_BOOKING,
      id: 'other-id',
      resourceId: 'other-resource-id',
      studentSub: 'other-student-uuid',
    }
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.endsWith('/api/me')) {
          return meResponse(['TECNICO'])
        }
        if (url.endsWith('/api/bookings')) {
          return new Response(JSON.stringify([OWN_BOOKING, other]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        throw new Error(`unexpected fetch: ${url}`)
      }),
    )

    renderBookingsPage()

    expect(await screen.findByText(OWN_BOOKING.resourceId)).toBeInTheDocument()
    expect(screen.getByTestId(`booking-item-${other.id}`)).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Crear reserva' })).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'APROBADA' })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: 'CANCELADA' })).toHaveLength(2)
  })

  it('shows an access-denied message for auditor.test and never calls GET /api/bookings', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/me')) {
        return meResponse(['AUDITOR'])
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderBookingsPage()

    expect(await screen.findByTestId('bookings-access-denied')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/api/bookings'))).toBe(false)
  })

  it('surfaces a 409 status-change conflict (e.g. a stale cached view) as a clear message, not a crash', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        if (url.endsWith('/api/me')) {
          return meResponse(['TECNICO'])
        }
        if (url.endsWith('/api/bookings')) {
          return new Response(JSON.stringify([{ ...OWN_BOOKING, status: 'APROBADA' }]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (url.includes(`/api/bookings/${OWN_BOOKING.id}/status`) && init?.method === 'PUT') {
          return new Response(
            JSON.stringify({ detail: 'Cannot transition a booking from DEVUELTA to EN_PREPARACION.' }),
            { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
          )
        }
        throw new Error(`unexpected fetch: ${url} ${init?.method}`)
      }),
    )

    const user = userEvent.setup()
    renderBookingsPage()

    await user.click(await screen.findByRole('button', { name: 'EN_PREPARACION' }))

    expect(
      await screen.findByText('Cannot transition a booking from DEVUELTA to EN_PREPARACION.'),
    ).toBeInTheDocument()
    expect(screen.getByText(OWN_BOOKING.resourceId)).toBeInTheDocument()
  })

  it('reviewer MINOR finding: estudiante.test sees a CANCELADA button on their own EN_PREPARACION booking ' +
    '(outside ESTUDIANTE_CANCEL_FROM), and clicking it surfaces the resulting 409 as a message, not a crash', async () => {
    const enPreparacionBooking = { ...OWN_BOOKING, status: 'EN_PREPARACION' }
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        if (url.endsWith('/api/me')) {
          return meResponse(['ESTUDIANTE'])
        }
        if (url.endsWith('/api/bookings') && (!init || init.method === undefined)) {
          return new Response(JSON.stringify([enPreparacionBooking]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (url.includes(`/api/bookings/${OWN_BOOKING.id}/status`) && init?.method === 'PUT') {
          return new Response(
            JSON.stringify({
              detail: 'Cannot transition a booking from EN_PREPARACION to CANCELADA.',
            }),
            { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
          )
        }
        throw new Error(`unexpected fetch: ${url} ${init?.method}`)
      }),
    )

    const user = userEvent.setup()
    renderBookingsPage()

    // Bug confirmation: the UI offers CANCELADA here even though ESTUDIANTE_CANCEL_FROM
    // is only {SOLICITADA, APROBADA} - BookingRow derives candidates from the staff
    // NEXT_STATUSES table filtered to CANCELADA, not from the narrower student window.
    const cancelButton = await screen.findByRole('button', { name: 'CANCELADA' })
    await user.click(cancelButton)

    // Server-side rejection (409) must surface as a visible message, never a crash or
    // silent failure (acceptance criterion 31).
    expect(
      await screen.findByText('Cannot transition a booking from EN_PREPARACION to CANCELADA.'),
    ).toBeInTheDocument()
    expect(screen.getByText(OWN_BOOKING.resourceId)).toBeInTheDocument()
  })
})
