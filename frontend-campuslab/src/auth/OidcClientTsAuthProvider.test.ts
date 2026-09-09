import { beforeEach, describe, expect, it, vi } from 'vitest'
import { OidcClientTsAuthProvider } from './OidcClientTsAuthProvider'

const mockEvents = {
  addUserLoaded: vi.fn().mockReturnValue(() => {}),
  addUserUnloaded: vi.fn().mockReturnValue(() => {}),
}

const mockUserManager = {
  events: mockEvents,
  getUser: vi.fn().mockResolvedValue(null),
  signinRedirect: vi.fn().mockResolvedValue(undefined),
  signoutRedirect: vi.fn().mockResolvedValue(undefined),
  signinRedirectCallback: vi.fn(),
}

vi.mock('oidc-client-ts', () => ({
  UserManager: vi.fn().mockImplementation(function UserManager() {
    return mockUserManager
  }),
}))

describe('OidcClientTsAuthProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUserManager.getUser.mockResolvedValue(null)
  })

  function newProvider() {
    return new OidcClientTsAuthProvider({
      authority: 'http://localhost:8081/realms/campuslab',
      clientId: 'campuslab-spa',
      apiScope: 'campuslab-api',
      redirectUri: 'http://localhost:5173/auth/callback',
      postLogoutRedirectUri: 'http://localhost:5173/login',
    })
  }

  it('isAuthenticated() is false until initialize() finds a non-expired user', async () => {
    const provider = newProvider()
    expect(provider.isAuthenticated()).toBe(false)

    mockUserManager.getUser.mockResolvedValue({ expired: false, access_token: 'token-1' });
    await provider.initialize()

    expect(provider.isAuthenticated()).toBe(true)
  })

  it('isAuthenticated() is false for an expired cached user', async () => {
    mockUserManager.getUser.mockResolvedValue({ expired: true, access_token: 'token-1' })
    const provider = newProvider()

    await provider.initialize()

    expect(provider.isAuthenticated()).toBe(false)
  })

  it('getAccessToken() returns null when there is no user', async () => {
    const provider = newProvider()

    await expect(provider.getAccessToken()).resolves.toBeNull()
  })

  it('getAccessToken() returns null for an expired user rather than a stale token', async () => {
    mockUserManager.getUser.mockResolvedValue({ expired: true, access_token: 'stale-token' })
    const provider = newProvider()

    await expect(provider.getAccessToken()).resolves.toBeNull()
  })

  it('handleRedirectCallback() completes the sign-in and updates isAuthenticated()', async () => {
    mockUserManager.signinRedirectCallback.mockResolvedValue({ expired: false, access_token: 'token-1' })
    const provider = newProvider()

    await provider.handleRedirectCallback()

    expect(provider.isAuthenticated()).toBe(true)
  })
})
