import type { SessionProvider } from './authRegistry'

/**
 * DEMO-ONLY. Not part of the production auth path.
 *
 * Entra ID is the only issuer in every real environment (docs/DECISIONES_PROFESOR.md #6),
 * and MsalAuthProvider remains the only implementation selected when VITE_AUTH_MODE is
 * anything other than "demo". This class exists for one narrow, documented situation:
 * the project tenant could not be created in time (see docs/DEMO_LOCAL.md), so the
 * browser has no identity provider to redirect to. It mints a token from the same
 * infra/testing/mock-jwks double that scripts/seed.sh already uses, through the Vite dev
 * proxy, so the rest of the system - BFF validation, role checks, ownership checks -
 * runs completely unchanged against a real RS256 token with real claims.
 *
 * It is unreachable unless VITE_AUTH_MODE=demo is set in frontend-campuslab/.env, it is
 * never referenced by the production build path, and it must be removed once the real
 * tenant exists.
 */

export interface DemoIdentity {
  key: string
  label: string
  role: 'ADMIN' | 'TECNICO' | 'ESTUDIANTE' | 'AUDITOR'
  oid: string
}

/**
 * OIDs deliberately identical to scripts/seed.sh's and scripts/verify-seed.sh's own
 * defaults, so a demo login lands on the data the seed already created. ESTUDIANTE2 is
 * the second student the IDOR check needs - it owns nothing, by design.
 */
export const DEMO_IDENTITIES: DemoIdentity[] = [
  { key: 'admin', label: 'Administrador', role: 'ADMIN', oid: 'c0000000-0000-4000-8000-0000000000a1' },
  { key: 'tecnico', label: 'Tecnico', role: 'TECNICO', oid: 'c0000000-0000-4000-8000-0000000000e2' },
  { key: 'estudiante', label: 'Estudiante', role: 'ESTUDIANTE', oid: 'c0000000-0000-4000-8000-0000000000e1' },
  { key: 'estudiante2', label: 'Estudiante 2 (IDOR)', role: 'ESTUDIANTE', oid: 'c0000000-0000-4000-8000-0000000000e3' },
  { key: 'auditor', label: 'Auditor', role: 'AUDITOR', oid: 'c0000000-0000-4000-8000-0000000000a2' },
]

const STORAGE_KEY = 'campuslab.demo.session'
/** Proxied by vite.config.ts to mock-jwks' /mock-tenant/v2.0, so the browser makes a same-origin call. */
const MINT_URL = '/mock-auth/mint'

export function isDemoAuthEnabled(): boolean {
  return import.meta.env.VITE_AUTH_MODE === 'demo'
}

export class DemoSessionProvider implements SessionProvider {
  private token: string | null = null
  private readonly listeners = new Set<() => void>()

  async initialize(): Promise<void> {
    this.token = sessionStorage.getItem(STORAGE_KEY)
  }

  /**
   * No identity provider to redirect to in demo mode - LoginPage renders the identity
   * picker and calls loginAs() instead. Kept as a no-op so the SessionProvider contract
   * (and every caller of useAuth().login) stays unchanged.
   */
  async login(): Promise<void> {}

  async loginAs(identity: DemoIdentity): Promise<void> {
    const response = await fetch(MINT_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ role: identity.role, oid: identity.oid }),
    })
    if (!response.ok) {
      throw new Error(
        `mock-jwks respondio ${response.status}. Esta corriendo? Ver docs/DEMO_LOCAL.md.`,
      )
    }
    const { token } = (await response.json()) as { token: string }
    this.token = token
    sessionStorage.setItem(STORAGE_KEY, token)
    this.notify()
  }

  async logout(): Promise<void> {
    this.token = null
    sessionStorage.removeItem(STORAGE_KEY)
    this.notify()
  }

  /** There is no redirect flow in demo mode, so /auth/callback has nothing to complete. */
  async handleRedirectCallback(): Promise<void> {}

  async getAccessToken(): Promise<string | null> {
    return this.token
  }

  isAuthenticated(): boolean {
    return this.token !== null
  }

  onAuthStateChanged(listener: () => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  private notify(): void {
    this.listeners.forEach((listener) => {
      listener()
    })
  }
}
