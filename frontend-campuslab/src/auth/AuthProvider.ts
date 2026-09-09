/**
 * Strategy interface: MSAL (Azure AD in deployment) and oidc-client-ts (local
 * Keycloak) are both genuinely required implementations (msal-browser hard-rejects
 * a plain-HTTP authority, see docs/decisiones/auth-frontend-oidc-local.md), so the
 * implementation is selected once by configuration, never by an `if` in a component.
 */
export interface AuthProvider {
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
