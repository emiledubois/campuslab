import { useEffect, useState } from 'react'
import { apiFetch } from '../api/httpClient'
import { useAuth } from '../auth/useAuth'

interface MeResponse {
  sub: string
  username: string
  email: string
  roles: string[]
  issuer: string
}

export function DashboardPage() {
  const { logout } = useAuth()
  const [me, setMe] = useState<MeResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false

    apiFetch('/api/me')
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`GET /api/me failed with status ${response.status}`)
        }
        return (await response.json()) as MeResponse
      })
      .then((data) => {
        if (!cancelled) {
          setMe(data)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : 'No se pudo cargar la identidad.')
        }
      })

    return () => {
      cancelled = true
    }
  }, [])

  return (
    <div className="flex flex-1 flex-col gap-6 p-8">
      <div className="flex items-center justify-between">
        <h2 className="text-xl font-medium text-slate-900">Dashboard</h2>
        <button
          type="button"
          onClick={() => {
            void logout()
          }}
          className="rounded border border-slate-300 px-3 py-1.5 text-slate-700 hover:bg-slate-100"
        >
          Cerrar sesion
        </button>
      </div>

      {error && <p className="text-red-600">{error}</p>}

      {me && (
        <dl className="grid max-w-md grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
          <dt className="font-medium text-slate-500">Usuario</dt>
          <dd data-testid="me-username">{me.username}</dd>
          <dt className="font-medium text-slate-500">Email</dt>
          <dd data-testid="me-email">{me.email}</dd>
          <dt className="font-medium text-slate-500">Roles</dt>
          <dd data-testid="me-roles">{me.roles.join(', ')}</dd>
          <dt className="font-medium text-slate-500">Emisor</dt>
          <dd data-testid="me-issuer">{me.issuer}</dd>
        </dl>
      )}
    </div>
  )
}
