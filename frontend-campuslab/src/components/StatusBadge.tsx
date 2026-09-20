export type BookingStatus = 'SOLICITADA' | 'APROBADA' | 'EN_PREPARACION' | 'EN_USO' | 'DEVUELTA' | 'CANCELADA'

const STATUS_STYLES: Record<BookingStatus, string> = {
  SOLICITADA: 'bg-amber-100 text-amber-800',
  APROBADA: 'bg-blue-100 text-blue-800',
  EN_PREPARACION: 'bg-indigo-100 text-indigo-800',
  EN_USO: 'bg-emerald-100 text-emerald-800',
  DEVUELTA: 'bg-slate-200 text-slate-700',
  CANCELADA: 'bg-red-100 text-red-700',
}

export function StatusBadge({ status }: { status: BookingStatus }) {
  return (
    <span
      data-testid={`status-badge-${status}`}
      className={`w-fit rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[status]}`}
    >
      {status}
    </span>
  )
}
