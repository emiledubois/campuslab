import { createContext } from 'react'

export interface AuthContextValue {
  isAuthenticated: boolean
  isLoading: boolean
  login: () => Promise<void>
  logout: () => Promise<void>
  handleRedirectCallback: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | undefined>(undefined)
