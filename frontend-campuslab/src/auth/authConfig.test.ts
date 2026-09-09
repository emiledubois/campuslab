import { afterEach, describe, expect, it, vi } from 'vitest'
import { resolveAuthProviderKind } from './authConfig'

describe('resolveAuthProviderKind', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('infers msal for an https authority', () => {
    vi.stubEnv('VITE_OIDC_AUTHORITY', 'https://login.microsoftonline.com/tenant-id/v2.0')
    vi.stubEnv('VITE_AUTH_PROVIDER', '')

    expect(resolveAuthProviderKind()).toBe('msal')
  })

  it('infers oidc-client-ts for a plain-http authority (local Keycloak)', () => {
    vi.stubEnv('VITE_OIDC_AUTHORITY', 'http://localhost:8081/realms/campuslab')
    vi.stubEnv('VITE_AUTH_PROVIDER', '')

    expect(resolveAuthProviderKind()).toBe('oidc-client-ts')
  })

  it('honours an explicit override regardless of the authority scheme', () => {
    vi.stubEnv('VITE_OIDC_AUTHORITY', 'https://login.microsoftonline.com/tenant-id/v2.0')
    vi.stubEnv('VITE_AUTH_PROVIDER', 'oidc-client-ts')

    expect(resolveAuthProviderKind()).toBe('oidc-client-ts')
  })
})
