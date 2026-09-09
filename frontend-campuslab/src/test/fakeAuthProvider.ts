import type { AuthProvider } from '../auth/AuthProvider'

export interface FakeAuthProviderOptions {
  authenticated?: boolean
  accessToken?: string | null
}

/**
 * Deterministic test double for AuthProvider - avoids coupling component/route
 * tests to a real MSAL/oidc-client-ts instance (network calls, redirects, storage).
 */
export function createFakeAuthProvider(options: FakeAuthProviderOptions = {}): AuthProvider {
  let authenticated = options.authenticated ?? false
  const accessToken = options.accessToken ?? (authenticated ? 'fake-access-token' : null)
  const listeners = new Set<() => void>()

  return {
    async initialize() {},
    async login() {
      authenticated = true
      listeners.forEach((listener) => listener())
    },
    async logout() {
      authenticated = false
      listeners.forEach((listener) => listener())
    },
    async handleRedirectCallback() {
      authenticated = true
      listeners.forEach((listener) => listener())
    },
    async getAccessToken() {
      return authenticated ? accessToken : null
    },
    isAuthenticated() {
      return authenticated
    },
    onAuthStateChanged(listener: () => void) {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
  }
}
