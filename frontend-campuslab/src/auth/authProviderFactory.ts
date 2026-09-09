import { readAuthConfig, resolveAuthProviderKind } from './authConfig'
import type { AuthProvider } from './AuthProvider'
import { MsalAuthProvider } from './MsalAuthProvider'
import { OidcClientTsAuthProvider } from './OidcClientTsAuthProvider'

export function createAuthProvider(): AuthProvider {
  const config = readAuthConfig()
  const kind = resolveAuthProviderKind()
  return kind === 'msal' ? new MsalAuthProvider(config) : new OidcClientTsAuthProvider(config)
}
