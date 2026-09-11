import { useEffect, useState } from 'react'
import { apiFetch } from '../api/httpClient'
import { useMe } from '../hooks/useMe'

type ReportRange = 'last24h' | 'last7d' | 'last30d'

const RANGES: ReportRange[] = ['last24h', 'last7d', 'last30d']

interface Bucket {
  hourStart: string
  count: number
}

interface KpisResponse {
  range: string
  generatedAt: string
  reservasPorHora: { bucketCount: number; buckets: Bucket[] }
  tiempoDeCiclo: { unit: string; averageSeconds: number | null; completedBookingsCount: number }
  equiposOcupados: { asOf: string; count: number; resourceIds: string[] }
}

interface ResourceApprovalCount {
  resourceId: string
  approvedCount: number
}

interface TopResourcesResponse {
  range: string
  generatedAt: string
  resources: ResourceApprovalCount[]
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
 * Role gating here is UX only - GET /api/report/kpis and GET /api/report/top-resources
 * are independently re-enforced by the BFF and by report itself (reporting.md §9 AC7),
 * so a user who bypassed this client-side check would still get 403 from the server,
 * never real data. Read-only, no filter form (design doc §2/§6) - just a range selector
 * shared by both endpoints, since both accept the same three-value enum.
 *
 * The two endpoints document different defaults for an omitted `range` (last24h for
 * kpis, last7d for top-resources, reporting.md §3). Until the admin actually touches
 * the shared selector, `range` is omitted from both requests entirely so each endpoint's
 * own server-side default applies; once changed, that value is sent explicitly to both.
 */
export function ReportsPage() {
  const { me, isLoading: meLoading } = useMe()
  const roles = me?.roles ?? []
  const canView = roles.includes('ADMIN')

  const [range, setRange] = useState<ReportRange>('last24h')
  const [rangeTouched, setRangeTouched] = useState(false)
  const [kpis, setKpis] = useState<KpisResponse | null>(null)
  const [topResources, setTopResources] = useState<TopResourcesResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (meLoading || !canView) {
      return
    }
    let cancelled = false

    const rangeQuery = rangeTouched ? `?range=${range}` : ''

    Promise.all([
      apiFetch(`/api/report/kpis${rangeQuery}`).then(async (response) => {
        if (!response.ok) {
          throw new Error(await errorMessageFor(response, `GET /api/report/kpis failed with status ${response.status}`))
        }
        return (await response.json()) as KpisResponse
      }),
      apiFetch(`/api/report/top-resources${rangeQuery}`).then(async (response) => {
        if (!response.ok) {
          throw new Error(
            await errorMessageFor(response, `GET /api/report/top-resources failed with status ${response.status}`),
          )
        }
        return (await response.json()) as TopResourcesResponse
      }),
    ])
      .then(([kpisData, topResourcesData]) => {
        if (!cancelled) {
          setError(null)
          setKpis(kpisData)
          setTopResources(topResourcesData)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'No se pudieron cargar los reportes.')
        }
      })

    return () => {
      cancelled = true
    }
  }, [meLoading, canView, range, rangeTouched])

  if (meLoading) {
    return null
  }

  if (!canView) {
    return (
      <div className="flex flex-1 flex-col p-8">
        <p data-testid="reports-access-denied" className="text-red-600">
          No tienes acceso a los reportes.
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-1 flex-col gap-6 p-8">
      <h2 className="text-xl font-medium text-slate-900">Reportes</h2>

      <label className="flex w-fit flex-col gap-1 text-sm">
        Rango
        <select
          aria-label="Rango"
          value={range}
          onChange={(event) => {
            setRange(event.target.value as ReportRange)
            setRangeTouched(true)
          }}
        >
          {RANGES.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
      </label>

      {error && <p className="text-red-600">{error}</p>}

      {kpis && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <section data-testid="reservas-por-hora-panel" className="rounded border border-slate-200 p-4">
            <h3 className="font-medium">Reservas por hora</h3>
            <p className="text-sm text-slate-500">{kpis.reservasPorHora.bucketCount} buckets</p>
            <ul className="mt-2 flex max-h-64 flex-col gap-1 overflow-y-auto text-sm">
              {kpis.reservasPorHora.buckets.map((bucket) => (
                <li key={bucket.hourStart}>
                  {bucket.hourStart}: {bucket.count}
                </li>
              ))}
            </ul>
          </section>

          <section data-testid="tiempo-de-ciclo-panel" className="rounded border border-slate-200 p-4">
            <h3 className="font-medium">Tiempo de ciclo</h3>
            <p className="text-sm text-slate-500">
              Promedio:{' '}
              {kpis.tiempoDeCiclo.averageSeconds === null ? 'sin datos' : `${kpis.tiempoDeCiclo.averageSeconds}s`}
            </p>
            <p className="text-sm text-slate-500">Reservas completadas: {kpis.tiempoDeCiclo.completedBookingsCount}</p>
          </section>

          <section data-testid="equipos-ocupados-panel" className="rounded border border-slate-200 p-4">
            <h3 className="font-medium">Equipos ocupados</h3>
            <p className="text-sm text-slate-500">{kpis.equiposOcupados.count} recursos ocupados</p>
            <ul className="mt-2 flex flex-col gap-1 text-sm">
              {kpis.equiposOcupados.resourceIds.map((resourceId) => (
                <li key={resourceId}>{resourceId}</li>
              ))}
            </ul>
          </section>
        </div>
      )}

      {topResources && (
        <section data-testid="top-resources-panel" className="rounded border border-slate-200 p-4">
          <h3 className="font-medium">Recursos mas usados</h3>
          {topResources.resources.length === 0 ? (
            <p>No hay datos para este rango.</p>
          ) : (
            <ul data-testid="top-resources-list" className="mt-2 flex flex-col gap-1 text-sm">
              {topResources.resources.map((resource) => (
                <li key={resource.resourceId} data-testid={`top-resource-${resource.resourceId}`}>
                  {resource.resourceId}: {resource.approvedCount}
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </div>
  )
}
