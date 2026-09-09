import { getActiveAuthProvider } from '../auth/authRegistry'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

/**
 * The only bearer-attaching entry point domain code should use - callers never
 * build the Authorization header themselves. If there is no active session (before
 * login, or after logout) the header is simply omitted, never sent stale or empty.
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const provider = getActiveAuthProvider()
  const token = provider ? await provider.getAccessToken() : null

  const headers = new Headers(init.headers)
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }

  return fetch(`${API_BASE_URL}${path}`, { ...init, headers })
}
