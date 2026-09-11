import { useState, type FormEvent } from 'react'
import { apiFetch } from '../api/httpClient'
import { useMe } from '../hooks/useMe'

type EventType =
  | 'BOOKING_SOLICITADA'
  | 'BOOKING_APROBADA'
  | 'BOOKING_EN_PREPARACION'
  | 'BOOKING_EN_USO'
  | 'BOOKING_DEVUELTA'
  | 'BOOKING_CANCELADA'

const EVENT_TYPES: EventType[] = [
  'BOOKING_SOLICITADA',
  'BOOKING_APROBADA',
  'BOOKING_EN_PREPARACION',
  'BOOKING_EN_USO',
  'BOOKING_DEVUELTA',
  'BOOKING_CANCELADA',
]

interface TimelineEvent {
  id: string
  eventId: string
  bookingId: string
  resourceId: string
  eventType: EventType
  actorOid: string
  actorRoles: string[]
  studentOid: string
  fromStatus: string | null
  toStatus: string
  traceId: string
  occurredAt: string
  receivedAt: string
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
 * Role gating here is UX only - GET /api/audit/timeline is independently re-enforced
 * by the BFF and by audit itself (kafka-audit.md §9 AC10), so a user who bypassed this
 * client-side check would still get 403 from the server, never real data.
 */
export function AuditPage() {
  const { me, isLoading: meLoading } = useMe()
  const roles = me?.roles ?? []
  const canView = roles.includes('ADMIN') || roles.includes('AUDITOR')

  const [events, setEvents] = useState<TimelineEvent[]>([])
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [hasSearched, setHasSearched] = useState(false)

  const [userOid, setUserOid] = useState('')
  const [eventType, setEventType] = useState<EventType | ''>('')
  const [bookingId, setBookingId] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setLoading(true)

    const params = new URLSearchParams()
    if (userOid) params.set('userOid', userOid)
    if (eventType) params.set('eventType', eventType)
    if (bookingId) params.set('bookingId', bookingId)
    if (from) params.set('from', new Date(from).toISOString())
    if (to) params.set('to', new Date(to).toISOString())

    try {
      const response = await apiFetch(`/api/audit/timeline?${params.toString()}`)
      if (!response.ok) {
        throw new Error(await errorMessageFor(response, `GET /api/audit/timeline failed with status ${response.status}`))
      }
      const data = (await response.json()) as TimelineEvent[]
      setEvents(data)
      setHasSearched(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo cargar el timeline de auditoria.')
    } finally {
      setLoading(false)
    }
  }

  if (meLoading) {
    return null
  }

  if (!canView) {
    return (
      <div className="flex flex-1 flex-col p-8">
        <p data-testid="audit-access-denied" className="text-red-600">
          No tienes acceso a la auditoria.
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-1 flex-col gap-6 p-8">
      <h2 className="text-xl font-medium text-slate-900">Auditoria</h2>

      <form onSubmit={handleSubmit} aria-label="Filtrar timeline" className="flex flex-col gap-3 rounded border border-slate-200 p-4">
        <label className="flex flex-col gap-1 text-sm">
          Usuario (oid)
          <input value={userOid} onChange={(event) => setUserOid(event.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          Tipo de evento
          <select value={eventType} onChange={(event) => setEventType(event.target.value as EventType | '')}>
            <option value="">Todos</option>
            {EVENT_TYPES.map((type) => (
              <option key={type} value={type}>
                {type}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-sm">
          Reserva (id)
          <input value={bookingId} onChange={(event) => setBookingId(event.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          Desde
          <input type="datetime-local" value={from} onChange={(event) => setFrom(event.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm">
          Hasta
          <input type="datetime-local" value={to} onChange={(event) => setTo(event.target.value)} />
        </label>
        {error && <p className="text-red-600">{error}</p>}
        <button
          type="submit"
          disabled={loading}
          className="w-fit rounded bg-slate-900 px-3 py-1.5 text-white hover:bg-slate-700"
        >
          Buscar
        </button>
      </form>

      {hasSearched && events.length === 0 && !error && <p>No hay eventos para estos filtros.</p>}

      <ul data-testid="audit-timeline-list" className="flex flex-col gap-3">
        {events.map((timelineEvent) => (
          <li key={timelineEvent.id} data-testid={`audit-item-${timelineEvent.id}`} className="rounded border border-slate-200 p-4">
            <p className="font-medium">{timelineEvent.eventType}</p>
            <p className="text-sm text-slate-500">
              {timelineEvent.fromStatus ?? '—'} {'->'} {timelineEvent.toStatus}
            </p>
            <p className="text-sm text-slate-500">actor: {timelineEvent.actorOid} ({timelineEvent.actorRoles.join(', ')})</p>
            <p className="text-sm text-slate-500">estudiante: {timelineEvent.studentOid}</p>
            <p className="text-sm text-slate-500">reserva: {timelineEvent.bookingId}</p>
            <p className="text-sm text-slate-500">{timelineEvent.occurredAt}</p>
          </li>
        ))}
      </ul>
    </div>
  )
}
