import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MsalAuthProvider } from './MsalAuthProvider'

const mockInstance = {
  initialize: vi.fn().mockResolvedValue(undefined),
  getAllAccounts: vi.fn().mockReturnValue([]),
  setActiveAccount: vi.fn(),
  getActiveAccount: vi.fn().mockReturnValue(null),
  loginRedirect: vi.fn().mockResolvedValue(undefined),
  logoutRedirect: vi.fn().mockResolvedValue(undefined),
  handleRedirectPromise: vi.fn().mockResolvedValue(null),
  acquireTokenSilent: vi.fn(),
  addEventCallback: vi.fn().mockReturnValue('callback-id'),
  removeEventCallback: vi.fn(),
}

vi.mock('@azure/msal-browser', () => ({
  PublicClientApplication: vi.fn().mockImplementation(function PublicClientApplication() {
    return mockInstance
  }),
}))

describe('MsalAuthProvider', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function newProvider() {
    return new MsalAuthProvider({
      authority: 'https://login.microsoftonline.com/tenant/v2.0',
      clientId: 'client-id',
      apiScope: 'api://client-id/.default',
      redirectUri: 'http://localhost:5173/auth/callback',
      postLogoutRedirectUri: 'http://localhost:5173/login',
    })
  }

  it('initialize() restores an existing active account without redirecting', async () => {
    mockInstance.getAllAccounts.mockReturnValue([{ homeAccountId: 'account-1' }])
    const provider = newProvider()

    await provider.initialize()

    expect(mockInstance.setActiveAccount).toHaveBeenCalledWith({ homeAccountId: 'account-1' })
    expect(mockInstance.loginRedirect).not.toHaveBeenCalled()
  })

  it('isAuthenticated() reflects whether MSAL has an active account', () => {
    const provider = newProvider()
    mockInstance.getActiveAccount.mockReturnValue(null)
    expect(provider.isAuthenticated()).toBe(false)

    mockInstance.getActiveAccount.mockReturnValue({ homeAccountId: 'account-1' })
    expect(provider.isAuthenticated()).toBe(true)
  })

  it('getAccessToken() returns null when there is no active account', async () => {
    mockInstance.getActiveAccount.mockReturnValue(null)
    const provider = newProvider()

    await expect(provider.getAccessToken()).resolves.toBeNull()
    expect(mockInstance.acquireTokenSilent).not.toHaveBeenCalled()
  })

  it('getAccessToken() returns null (never throws) when silent acquisition fails', async () => {
    mockInstance.getActiveAccount.mockReturnValue({ homeAccountId: 'account-1' })
    mockInstance.acquireTokenSilent.mockRejectedValue(new Error('interaction_required'))
    const provider = newProvider()

    await expect(provider.getAccessToken()).resolves.toBeNull()
  })
})
