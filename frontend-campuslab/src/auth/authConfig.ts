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
