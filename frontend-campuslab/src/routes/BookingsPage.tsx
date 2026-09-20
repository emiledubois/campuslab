import { useEffect, useState, type FormEvent } from 'react'
import { apiFetch } from '../api/httpClient'
import { AsyncStateNotice } from '../components/AsyncStateNotice'
import { PageContainer, PageHeading } from '../components/Page'
import { StatusBadge, type BookingStatus } from '../components/StatusBadge'
import { useMe } from '../hooks/useMe'
import { canViewBookings } from './roleAccess'

interface Booking {
  id: string
  resourceId: string
  studentOid: string
  requestedStart: string
  requestedEnd: string
  notes: string | null
  status: BookingStatus
  version: number
  createdAt: string
  updatedAt: string
}

// Mirrors ms-campuslab-bookings' own STAFF_TRANSITIONS table (design doc §3) - the
// server is the actual source of truth (see AC9-14), this only drives which buttons
// the UI offers; a stale/bypassed client-side offer still gets rejected server-side.
const NEXT_STATUSES: Record<BookingStatus, BookingStatus[]> = {
  SOLICITADA: ['APROBADA', 'CANCELADA'],
  APROBADA: ['EN_PREPARACION', 'CANCELADA'],
  EN_PREPARACION: ['EN_USO', 'CANCELADA'],
  EN_USO: ['DEVUELTA'],
  DEVUELTA: [],
  CANCELADA: [],
}

async function errorMessageFor(response: Response, fallback: string): Promise<string> {
  try {
    const problem = (await response.json()) as { detail?: string }
    return problem.detail ?? fallback
  } catch {
    return fallback
  }
}

function formatDateTime(iso: string): string {
  return new Intl.DateTimeFormat('es-CL', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(iso))
}

/**
 * Role gating here is UX only - GET/POST/PUT are independently re-enforced by the
 * BFF and by bookings itself (see acceptance criteria 1-22), so a user who bypassed
 * this client-side check would still get 403/404 from the server, never real data.
 */
export function BookingsPage() {
  const { me, isLoading: meLoading } = useMe()
  const roles = me?.roles ?? []
  const isEstudiante = roles.includes('ESTUDIANTE')
  const isStaff = roles.includes('TECNICO') || roles.includes('ADMIN')
  const canView = canViewBookings(roles)

  const [bookings, setBookings] = useState<Booking[]>([])
  const [listError, setListError] = useState<string | null>(null)
  const [listLoading, setListLoading] = useState(true)

  useEffect(() => {
    if (meLoading || !canView) {
      return
    }
    let cancelled = false

    apiFetch('/api/bookings')
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`GET /api/bookings failed with status ${response.status}`)
        }
        return (await response.json()) as Booking[]
      })
      .then((data) => {
        if (!cancelled) {
          setBookings(data)
          setListError(null)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setListError(err instanceof Error ? err.message : 'No se pudieron cargar las reservas.')
        }
      })
      .finally(() => {
        if (!cancelled) {
          setListLoading(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [meLoading, canView])

  if (meLoading) {
    return null
  }

  if (!canView) {
    return (
      <div className="flex flex-1 flex-col p-8">
        <p data-testid="bookings-access-denied" className="text-red-600">
          No tienes acceso a las reservas.
        </p>
      </div>
    )
  }

  return (
    <PageContainer>
      <PageHeading>Reservas</PageHeading>

      {listLoading && <AsyncStateNotice kind="loading" message="Cargando reservas..." />}
      {!listLoading && listError && <AsyncStateNotice kind="error" message={listError} />}
      {!listLoading && !listError && bookings.length === 0 && (
        <AsyncStateNotice
          kind="empty"
          message={isEstudiante ? 'No tienes reservas.' : 'No hay reservas registradas.'}
        />
      )}

      {isEstudiante && (
        <CreateBookingForm onCreated={(created) => setBookings((current) => [...current, created])} />
      )}

      <ul data-testid="bookings-list" className="flex flex-col gap-3">
        {bookings.map((booking) => (
          <BookingRow
            key={booking.id}
            booking={booking}
            isStaff={isStaff}
            onUpdated={(updated) =>
              setBookings((current) => current.map((existing) => (existing.id === updated.id ? updated : existing)))
            }
          />
        ))}
      </ul>
    </PageContainer>
  )
}

function CreateBookingForm({ onCreated }: { onCreated: (booking: Booking) => void }) {
  const [resourceId, setResourceId] = useState('')
  const [requestedStart, setRequestedStart] = useState('')
  const [requestedEnd, setRequestedEnd] = useState('')
  const [notes, setNotes] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)

    const body = {
      resourceId,
      requestedStart: new Date(requestedStart).toISOString(),
      requestedEnd: new Date(requestedEnd).toISOString(),
      notes: notes || null,
    }

    try {
      const response = await apiFetch('/api/bookings', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!response.ok) {
        throw new Error(await errorMessageFor(response, `POST /api/bookings failed with status ${response.status}`))
      }
      const created = (await response.json()) as Booking
      onCreated(created)
      setResourceId('')
      setRequestedStart('')
      setRequestedEnd('')
      setNotes('')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo crear la reserva.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      aria-label="Crear reserva"
      className="flex flex-col gap-3 rounded border border-slate-200 p-4"
    >
      <label className="flex flex-col gap-1 text-sm">
        Recurso (id)
        <input value={resourceId} onChange={(event) => setResourceId(event.target.value)} required />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        Inicio
        <input
          type="datetime-local"
          value={requestedStart}
          onChange={(event) => setRequestedStart(event.target.value)}
          required
        />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        Fin
        <input
          type="datetime-local"
          value={requestedEnd}
          onChange={(event) => setRequestedEnd(event.target.value)}
          required
        />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        Notas
        <input value={notes} onChange={(event) => setNotes(event.target.value)} />
      </label>
      {error && <p className="text-red-600">{error}</p>}
      <button
        type="submit"
        disabled={submitting}
        className="w-fit rounded bg-slate-900 px-3 py-1.5 text-white hover:bg-slate-700"
      >
        Solicitar
      </button>
    </form>
  )
}

/**
 * Shortened resource id, full UUID kept in title= for every role uniformly (design
 * doc §5c): GET /api/catalog/resources - the only way to resolve a name - is
 * ADMIN/TECNICO-only, and widening it or adding backend fields to fix this display
 * gap is explicitly out of scope for this frontend-only slice. Accepted limitation,
 * not a bug.
 */
function BookingRow({
  booking,
  isStaff,
  onUpdated,
}: {
  booking: Booking
  isStaff: boolean
  onUpdated: (booking: Booking) => void
}) {
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const candidates = isStaff
    ? NEXT_STATUSES[booking.status]
    : NEXT_STATUSES[booking.status].filter((status) => status === 'CANCELADA')

  async function changeStatus(status: BookingStatus) {
    setError(null)
    setSubmitting(true)
    try {
      const response = await apiFetch(`/api/bookings/${booking.id}/status`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status }),
      })
      if (!response.ok) {
        throw new Error(
          await errorMessageFor(response, `PUT /api/bookings/${booking.id}/status failed with status ${response.status}`),
        )
      }
      const updated = (await response.json()) as Booking
      onUpdated(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo cambiar el estado de la reserva.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <li data-testid={`booking-item-${booking.id}`} className="rounded border border-slate-200 p-4">
      <div className="flex items-center justify-between gap-3">
        <p className="font-medium" title={booking.resourceId}>
          Recurso {booking.resourceId.slice(0, 8)}...
        </p>
        <StatusBadge status={booking.status} />
      </div>
      <p className="text-sm text-slate-500">
        {formatDateTime(booking.requestedStart)} - {formatDateTime(booking.requestedEnd)}
      </p>
      <p className="text-sm text-slate-500">
        {booking.notes ?? <span className="text-slate-400">Sin notas</span>}
      </p>
      {error && <p className="text-red-600">{error}</p>}
      {candidates.length > 0 && (
        <div className="mt-2 flex gap-2">
          {candidates.map((status) => (
            <button
              key={status}
              type="button"
              disabled={submitting}
              onClick={() => changeStatus(status)}
              className="rounded border border-slate-300 px-3 py-1 text-sm hover:bg-slate-100"
            >
              {status}
            </button>
          ))}
        </div>
      )}
    </li>
  )
}
