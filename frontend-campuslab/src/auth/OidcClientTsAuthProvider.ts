import { UserManager, type User, type UserManagerSettings } from 'oidc-client-ts'
import type { AuthConfig } from './authConfig'
import type { AuthProvider } from './AuthProvider'

/**
 * Local Keycloak. msal-browser hard-rejects Keycloak's plain-HTTP local authority
 * (see docs/decisiones/auth-frontend-oidc-local.md) - oidc-client-ts has no such
 * restriction, so this is the local-dev counterpart to MsalAuthProvider.
 */
export class OidcClientTsAuthProvider implements AuthProvider {
  private readonly userManager: UserManager
  private cachedUser: User | null = null

  constructor(config: AuthConfig) {
    const settings: UserManagerSettings = {
      authority: config.authority,
      client_id: config.clientId,
      redirect_uri: config.redirectUri,
      post_logout_redirect_uri: config.postLogoutRedirectUri,
      response_type: 'code',
      scope: `openid ${config.apiScope}`,
      automaticSilentRenew: true,
    }
    this.userManager = new UserManager(settings)
    this.userManager.events.addUserLoaded((user) => {
      this.cachedUser = user
    })
    this.userManager.events.addUserUnloaded(() => {
      this.cachedUser = null
    })
  }

  async initialize(): Promise<void> {
    this.cachedUser = await this.userManager.getUser()
  }

  async login(): Promise<void> {
    await this.userManager.signinRedirect()
  }

  async logout(): Promise<void> {
    await this.userManager.signoutRedirect()
  }

  async handleRedirectCallback(): Promise<void> {
    this.cachedUser = await this.userManager.signinRedirectCallback()
  }

  async getAccessToken(): Promise<string | null> {
    const user = await this.userManager.getUser()
    if (!user || user.expired) {
      return null
    }
    return user.access_token
  }

  isAuthenticated(): boolean {
    return this.cachedUser !== null && !this.cachedUser.expired
  }

  onAuthStateChanged(listener: () => void): () => void {
    const unsubscribeLoaded = this.userManager.events.addUserLoaded(() => listener())
    const unsubscribeUnloaded = this.userManager.events.addUserUnloaded(() => listener())
    return () => {
      unsubscribeLoaded()
      unsubscribeUnloaded()
    }
  }
}
