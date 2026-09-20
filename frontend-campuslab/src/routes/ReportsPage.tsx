import { useEffect, useState } from 'react'
import { apiFetch } from '../api/httpClient'
import { AsyncStateNotice } from '../components/AsyncStateNotice'
import { Card, PageContainer, PageHeading } from '../components/Page'
import { useMe } from '../hooks/useMe'
import { canViewReports } from './roleAccess'

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
 * CSS/Tailwind bar chart, no charting library: bar height is the one data-driven
 * inline style, everything else is Tailwind layout classes. Collapses to the shared
 * empty-state notice when every bucket is zero rather than rendering a flat,
 * uninformative row of zero-height bars.
 *
 * Contained within its own card: each bar has a fixed min-width (not flex-1, which
 * can shrink to 0 and force sibling content to overflow) and the row scrolls
 * horizontally inside `overflow-x-auto` rather than spilling past the card boundary
 * when there isn't room for every bucket at once. Hour labels are thinned to every
 * third bucket to stay legible at that width; every bar keeps its full count in a
 * `title` tooltip regardless of whether its label is shown.
 */
function ReservasPorHoraChart({ buckets }: { buckets: Bucket[] }) {
  const max = Math.max(1, ...buckets.map((bucket) => bucket.count))
  const hourFormatter = new Intl.DateTimeFormat('es-CL', { hour: '2-digit', minute: '2-digit' })

  return (
    <div className="mt-3 overflow-x-auto">
      <div
        data-testid="reservas-por-hora-chart"
        role="img"
        aria-label="Reservas por hora"
        className="flex h-32 items-end gap-1"
      >
        {buckets.map((bucket, index) => (
          <div
            key={bucket.hourStart}
            data-testid={`reservas-bar-${bucket.hourStart}`}
            title={`${hourFormatter.format(new Date(bucket.hourStart))}: ${bucket.count} reservas`}
            className="flex h-full w-7 min-w-7 shrink-0 flex-col items-center justify-end gap-1"
          >
            <div
              className="w-full rounded-t bg-slate-700"
              style={{ height: `${(bucket.count / max) * 100}%` }}
            />
            <span className="text-[10px] text-slate-500">
              {index % 3 === 0 ? hourFormatter.format(new Date(bucket.hourStart)) : ''}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
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
  const canView = canViewReports(roles)

  const [range, setRange] = useState<ReportRange>('last24h')
  const [rangeTouched, setRangeTouched] = useState(false)
  const [kpis, setKpis] = useState<KpisResponse | null>(null)
  const [topResources, setTopResources] = useState<TopResourcesResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)

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
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
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
    <PageContainer>
      <PageHeading>Reportes</PageHeading>

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

      {loading && <AsyncStateNotice kind="loading" message="Cargando reportes..." />}
      {!loading && error && <AsyncStateNotice kind="error" message={error} />}

      {!loading && kpis && (
        <div className="flex flex-col gap-4">
          <Card data-testid="reservas-por-hora-panel">
            <h3 className="font-medium">Reservas por hora</h3>
            {kpis.reservasPorHora.buckets.some((bucket) => bucket.count > 0) ? (
              <ReservasPorHoraChart buckets={kpis.reservasPorHora.buckets} />
            ) : (
              <AsyncStateNotice kind="empty" message="Sin reservas en este rango." />
            )}
          </Card>

          <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
            <Card data-testid="tiempo-de-ciclo-panel">
              <h3 className="font-medium">Tiempo de ciclo</h3>
              <p className="text-sm text-slate-500">
                Promedio:{' '}
                {kpis.tiempoDeCiclo.averageSeconds === null ? 'sin datos' : `${kpis.tiempoDeCiclo.averageSeconds}s`}
              </p>
              <p className="text-sm text-slate-500">
                Reservas completadas: {kpis.tiempoDeCiclo.completedBookingsCount}
              </p>
            </Card>

            <Card data-testid="equipos-ocupados-panel">
              <h3 className="font-medium">Equipos ocupados</h3>
              <p className="text-sm text-slate-500">{kpis.equiposOcupados.count} recursos ocupados</p>
              <ul className="mt-2 flex flex-col gap-1 text-sm">
                {kpis.equiposOcupados.resourceIds.map((resourceId) => (
                  <li key={resourceId}>{resourceId}</li>
                ))}
              </ul>
            </Card>
          </div>
        </div>
      )}

      {!loading && topResources && (
        <Card data-testid="top-resources-panel">
          <h3 className="font-medium">Recursos mas usados</h3>
          {topResources.resources.length === 0 ? (
            <AsyncStateNotice kind="empty" message="No hay datos para este rango." />
          ) : (
            <ul data-testid="top-resources-list" className="mt-2 flex flex-col gap-1 text-sm">
              {topResources.resources.map((resource) => (
                <li key={resource.resourceId} data-testid={`top-resource-${resource.resourceId}`}>
                  {resource.resourceId}: {resource.approvedCount}
                </li>
              ))}
            </ul>
          )}
        </Card>
      )}
    </PageContainer>
  )
}
