import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { readAuthConfig } from './authConfig'
import { AuthContext } from './authContext'
import { MsalAuthProvider } from './MsalAuthProvider'
import { setActiveAuthProvider, type SessionProvider } from './authRegistry'

interface AuthContextProviderProps {
  children: ReactNode
  /** Test-only injection point; production always uses MsalAuthProvider, unconditionally. */
  provider?: SessionProvider
}

export function AuthContextProvider({ children, provider: providerOverride }: AuthContextProviderProps) {
  const [provider] = useState<SessionProvider>(() => providerOverride ?? new MsalAuthProvider(readAuthConfig()))
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
