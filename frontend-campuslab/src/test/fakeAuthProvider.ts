import type { SessionProvider } from '../auth/authRegistry'

export interface FakeAuthProviderOptions {
  authenticated?: boolean
  accessToken?: string | null
}

/**
 * Deterministic test double for SessionProvider - avoids coupling component/route
 * tests to a real MSAL instance (network calls, redirects, session storage).
 */
export function createFakeAuthProvider(options: FakeAuthProviderOptions = {}): SessionProvider {
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
