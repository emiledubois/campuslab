import { PublicClientApplication } from '@azure/msal-browser'
import type { AuthConfig } from './authConfig'
import type { AuthProvider } from './AuthProvider'

/**
 * Azure AD in deployment. Native `msal-browser` support - no workaround needed
 * here, unlike the local Keycloak path (see OidcClientTsAuthProvider).
 */
export class MsalAuthProvider implements AuthProvider {
  private readonly msalInstance: PublicClientApplication
  private readonly scopes: string[]

  constructor(config: AuthConfig) {
    this.scopes = [config.apiScope]
    this.msalInstance = new PublicClientApplication({
      auth: {
        clientId: config.clientId,
        authority: config.authority,
        redirectUri: config.redirectUri,
        postLogoutRedirectUri: config.postLogoutRedirectUri,
      },
      cache: {
        cacheLocation: 'sessionStorage',
      },
    })
  }

  async initialize(): Promise<void> {
    await this.msalInstance.initialize()
    const account = this.msalInstance.getAllAccounts()[0] ?? null
    if (account) {
      this.msalInstance.setActiveAccount(account)
    }
  }

  async login(): Promise<void> {
    await this.msalInstance.loginRedirect({ scopes: this.scopes })
  }

  async logout(): Promise<void> {
    await this.msalInstance.logoutRedirect()
  }

  async handleRedirectCallback(): Promise<void> {
    const result = await this.msalInstance.handleRedirectPromise()
    if (result?.account) {
      this.msalInstance.setActiveAccount(result.account)
    }
  }

  async getAccessToken(): Promise<string | null> {
    const account = this.msalInstance.getActiveAccount()
    if (!account) {
      return null
    }
    try {
      const result = await this.msalInstance.acquireTokenSilent({ scopes: this.scopes, account })
      return result.accessToken
    } catch {
      return null
    }
  }

  isAuthenticated(): boolean {
    return this.msalInstance.getActiveAccount() !== null
  }

  onAuthStateChanged(listener: () => void): () => void {
    const callbackId = this.msalInstance.addEventCallback(() => listener())
    return () => {
      if (callbackId) {
        this.msalInstance.removeEventCallback(callbackId)
      }
    }
  }
}
