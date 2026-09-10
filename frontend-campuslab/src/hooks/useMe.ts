import { useEffect, useState } from 'react'
import { apiFetch } from '../api/httpClient'

export interface MeResponse {
  sub: string
  oid: string
  username: string
  email: string
  roles: string[]
  issuer: string
}

interface UseMeResult {
  me: MeResponse | null
  error: string | null
  isLoading: boolean
}

/**
 * Factored out of DashboardPage's original inline GET /api/me call so CatalogPage
 * can reuse it for role-based UI gating without duplicating fetch/error-handling
 * logic - the server-side role check (catalog's own SecurityConfig path rules) is
 * what actually enforces access; this hook only drives what the UI shows.
 */
export function useMe(): UseMeResult {
  const [me, setMe] = useState<MeResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [isLoading, setIsLoading] = useState(true)

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
      .finally(() => {
        if (!cancelled) {
          setIsLoading(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [])

  return { me, error, isLoading }
}
