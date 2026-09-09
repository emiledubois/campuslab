import type { AuthProvider } from './AuthProvider'

/**
 * Lets the shared HTTP client (src/api/httpClient.ts) read the current access
 * token without importing React/context - kept separate from AuthContext so a
 * plain fetch wrapper never needs to know about component tree.
 */
let activeAuthProvider: AuthProvider | null = null

export function setActiveAuthProvider(provider: AuthProvider | null): void {
  activeAuthProvider = provider
}

export function getActiveAuthProvider(): AuthProvider | null {
  return activeAuthProvider
}
