import { useEffect, useState, type FormEvent } from 'react'
import { apiFetch } from '../api/httpClient'
import { useMe } from '../hooks/useMe'

type BookingStatus = 'SOLICITADA' | 'APROBADA' | 'EN_PREPARACION' | 'EN_USO' | 'DEVUELTA' | 'CANCELADA'

interface Booking {
  id: string
  resourceId: string
  studentSub: string
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
  const canView = isEstudiante || isStaff

  const [bookings, setBookings] = useState<Booking[]>([])
  const [listError, setListError] = useState<string | null>(null)

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
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setListError(err instanceof Error ? err.message : 'No se pudieron cargar las reservas.')
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
    <div className="flex flex-1 flex-col gap-6 p-8">
      <h2 className="text-xl font-medium text-slate-900">Reservas</h2>

      {listError && <p className="text-red-600">{listError}</p>}

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
    </div>
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
      <p className="font-medium">{booking.resourceId}</p>
      <p className="text-sm text-slate-500">{booking.status}</p>
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
