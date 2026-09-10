/**
 * Entra ID is the only issuer, in every environment (docs/DECISIONES_PROFESOR.md #6),
 * so there is exactly one production implementation (MsalAuthProvider), selected
 * unconditionally - this is not a Strategy (a family of interchangeable algorithms).
 * The type below exists solely to keep the existing test-substitution seam alive:
 * AuthContextProvider accepts an optional `provider` override so component/route tests
 * can inject createFakeAuthProvider() instead of a real MsalAuthProvider, avoiding real
 * MSAL redirects, session storage and network calls in unit tests. A plain
 * dependency-injection seam, not a Strategy - see docs/designs/entra-migration.md §6.
 */
export interface SessionProvider {
  /** Restores any existing session on app startup. Must not redirect. */
  initialize(): Promise<void>
  /** Redirects the browser to the identity provider's login page. */
  login(): Promise<void>
  /** Clears the local session and redirects to the identity provider's logout endpoint. */
  logout(): Promise<void>
  /** Completes the redirect flow on the dedicated /auth/callback route. */
  handleRedirectCallback(): Promise<void>
  /** Returns the current access token, or null if there is no authenticated session. */
  getAccessToken(): Promise<string | null>
  isAuthenticated(): boolean
  /** Subscribes to session changes (login/logout/token refresh); returns an unsubscribe function. */
  onAuthStateChanged(listener: () => void): () => void
}

/**
 * Lets the shared HTTP client (src/api/httpClient.ts) read the current access
 * token without importing React/context - kept separate from AuthContext so a
 * plain fetch wrapper never needs to know about component tree.
 */
let activeAuthProvider: SessionProvider | null = null

export function setActiveAuthProvider(provider: SessionProvider | null): void {
  activeAuthProvider = provider
}

export function getActiveAuthProvider(): SessionProvider | null {
  return activeAuthProvider
}
