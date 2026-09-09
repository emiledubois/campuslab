export interface AuthConfig {
  authority: string
  clientId: string
  apiScope: string
  redirectUri: string
  postLogoutRedirectUri: string
}

export function readAuthConfig(): AuthConfig {
  const authority = import.meta.env.VITE_OIDC_AUTHORITY ?? ''
  const clientId = import.meta.env.VITE_OIDC_CLIENT_ID ?? ''
  const apiScope = import.meta.env.VITE_API_SCOPE ?? ''

  return {
    authority,
    clientId,
    apiScope,
    redirectUri: `${window.location.origin}/auth/callback`,
    postLogoutRedirectUri: `${window.location.origin}/login`,
  }
}

export type AuthProviderKind = 'msal' | 'oidc-client-ts'

/**
 * Never an `if` on issuer in application code: the provider kind is either an
 * explicit env override, or inferred once from the authority's URL scheme
 * (msal-browser rejects any non-https authority outright).
 */
export function resolveAuthProviderKind(): AuthProviderKind {
  const explicit = import.meta.env.VITE_AUTH_PROVIDER
  if (explicit === 'msal' || explicit === 'oidc-client-ts') {
    return explicit
  }
  const authority = import.meta.env.VITE_OIDC_AUTHORITY ?? ''
  return authority.startsWith('https:') ? 'msal' : 'oidc-client-ts'
}
