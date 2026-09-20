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

  it('initialize() consumes the pending redirect response, after MSAL init, and activates its account', async () => {
    const redirectAccount = { homeAccountId: 'redirect-account' }
    mockInstance.getAllAccounts.mockReturnValue([{ homeAccountId: 'stale-cached-account' }])
    mockInstance.handleRedirectPromise.mockResolvedValue({ account: redirectAccount })
    const provider = newProvider()

    await provider.initialize()

    expect(mockInstance.initialize.mock.invocationCallOrder[0]).toBeLessThan(
      mockInstance.handleRedirectPromise.mock.invocationCallOrder[0],
    )
    expect(mockInstance.setActiveAccount).toHaveBeenCalledTimes(1)
    expect(mockInstance.setActiveAccount).toHaveBeenCalledWith(redirectAccount)
  })

  it('initialize() falls back to the first cached account when there is no redirect response', async () => {
    mockInstance.getAllAccounts.mockReturnValue([{ homeAccountId: 'cached-account' }])
    mockInstance.handleRedirectPromise.mockResolvedValue(null)
    const provider = newProvider()

    await provider.initialize()

    expect(mockInstance.handleRedirectPromise).toHaveBeenCalledTimes(1)
    expect(mockInstance.setActiveAccount).toHaveBeenCalledWith({ homeAccountId: 'cached-account' })
  })

  it('initialize() logs a failing handleRedirectPromise() and still resolves, falling back to the cached account', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    mockInstance.getAllAccounts.mockReturnValue([{ homeAccountId: 'cached-account' }])
    mockInstance.handleRedirectPromise.mockRejectedValue(new Error('AADSTS50011: redirect uri mismatch'))
    const provider = newProvider()

    await expect(provider.initialize()).resolves.toBeUndefined()

    expect(consoleError).toHaveBeenCalledTimes(1)
    expect(mockInstance.setActiveAccount).toHaveBeenCalledWith({ homeAccountId: 'cached-account' })
    consoleError.mockRestore()
  })

  it('keeps the account active when the redirect was consumed in initialize() and the callback page asks again', async () => {
    const redirectAccount = { homeAccountId: 'redirect-account' }
    let activeAccount: unknown = null
    mockInstance.setActiveAccount.mockImplementation((account: unknown) => {
      activeAccount = account
    })
    mockInstance.getActiveAccount.mockImplementation(() => activeAccount)
    mockInstance.getAllAccounts.mockReturnValue([])
    // MSAL 5.x memoizes the first result per page load, so the callback's own call gets the
    // same response back; older behaviour returned null. Both must leave the session intact.
    for (const secondCall of [{ account: redirectAccount }, null]) {
      activeAccount = null
      mockInstance.handleRedirectPromise.mockReset()
      mockInstance.handleRedirectPromise.mockResolvedValueOnce({ account: redirectAccount })
      mockInstance.handleRedirectPromise.mockResolvedValueOnce(secondCall)
      const provider = newProvider()

      await provider.initialize()
      await provider.handleRedirectCallback()

      expect(provider.isAuthenticated()).toBe(true)
    }
    mockInstance.setActiveAccount.mockReset()
    mockInstance.getActiveAccount.mockReset().mockReturnValue(null)
    mockInstance.handleRedirectPromise.mockReset().mockResolvedValue(null)
  })

  it('handleRedirectCallback() still rejects on a real redirect failure so the callback page can show it', async () => {
    mockInstance.handleRedirectPromise.mockRejectedValue(new Error('interaction_in_progress'))
    const provider = newProvider()

    await expect(provider.handleRedirectCallback()).rejects.toThrow('interaction_in_progress')
    mockInstance.handleRedirectPromise.mockReset().mockResolvedValue(null)
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
