import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { readAuthConfig } from './authConfig'
import { AuthContext } from './authContext'
import { MsalAuthProvider } from './MsalAuthProvider'
import { DemoSessionProvider, isDemoAuthEnabled } from './DemoSessionProvider'
import { setActiveAuthProvider, type SessionProvider } from './authRegistry'

interface AuthContextProviderProps {
  children: ReactNode
  /** Test-only injection point; production always uses MsalAuthProvider, unconditionally. */
  provider?: SessionProvider
}

/**
 * Provider selection: MsalAuthProvider always, except when VITE_AUTH_MODE=demo, a
 * documented temporary local-demo mode that exists only while the project tenant cannot
 * be created (docs/DEMO_LOCAL.md). Unset the variable and this branch is dead.
 */
function createProvider(): SessionProvider {
  return isDemoAuthEnabled() ? new DemoSessionProvider() : new MsalAuthProvider(readAuthConfig())
}

export function AuthContextProvider({ children, provider: providerOverride }: AuthContextProviderProps) {
  const [provider] = useState<SessionProvider>(() => providerOverride ?? createProvider())
  const [isLoading, setIsLoading] = useState(true)
  const [isAuthenticated, setIsAuthenticated] = useState(false)

  useEffect(() => {
    setActiveAuthProvider(provider)
    let cancelled = false

    provider.initialize().then(() => {
      if (cancelled) {
        return
      }
      setIsAuthenticated(provider.isAuthenticated())
      setIsLoading(false)
    })

    const unsubscribe = provider.onAuthStateChanged(() => {
      setIsAuthenticated(provider.isAuthenticated())
    })

    return () => {
      cancelled = true
      unsubscribe()
      setActiveAuthProvider(null)
    }
  }, [provider])

  const login = useCallback(() => provider.login(), [provider])
  const logout = useCallback(() => provider.logout(), [provider])
  const handleRedirectCallback = useCallback(async () => {
    await provider.handleRedirectCallback()
    setIsAuthenticated(provider.isAuthenticated())
  }, [provider])

  return (
    <AuthContext.Provider value={{ isAuthenticated, isLoading, login, logout, handleRedirectCallback }}>
      {children}
    </AuthContext.Provider>
  )
}
