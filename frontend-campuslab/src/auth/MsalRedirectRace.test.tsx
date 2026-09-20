import { PublicClientApplication } from '@azure/msal-browser'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthCallbackPage } from '../routes/AuthCallbackPage'
import { AuthContextProvider } from './AuthContext'
import { MsalAuthProvider } from './MsalAuthProvider'

/**
 * Real MSAL, real React tree, no fake provider: the ordering bug lives in the interaction
 * between React's child-before-parent effect order and MSAL's "initialize() first" rule,
 * which a fake SessionProvider cannot reproduce. Makes no network call - with no redirect
 * in progress MSAL never leaves the page. Does NOT prove a real Entra redirect response is
 * processed; that stays a manual check (docs/E2E_CHECKLIST.md).
 */
describe('MsalAuthProvider + AuthCallbackPage against the real MSAL library', () => {
  beforeEach(() => {
    sessionStorage.clear()
  })

  afterEach(() => {
    vi.restoreAllMocks()
    sessionStorage.clear()
  })

  it('never calls handleRedirectPromise() before MSAL is initialized and surfaces the outcome instead of redirecting silently', async () => {
    const handleRedirectPromise = vi.spyOn(PublicClientApplication.prototype, 'handleRedirectPromise')
    const provider = new MsalAuthProvider({
      authority: 'https://login.microsoftonline.com/00000000-0000-4000-8000-000000000000/v2.0',
      clientId: '11111111-1111-4111-8111-111111111111',
      apiScope: 'api://11111111-1111-4111-8111-111111111111/.default',
      redirectUri: 'http://localhost:3000/auth/callback',
      postLogoutRedirectUri: 'http://localhost:3000/login',
    })

    render(
      <MemoryRouter initialEntries={['/auth/callback']}>
        <AuthContextProvider provider={provider}>
          <Routes>
            <Route path="/auth/callback" element={<AuthCallbackPage />} />
            <Route path="/dashboard" element={<div>dashboard-landed</div>} />
            <Route path="/login" element={<div>login-landed</div>} />
          </Routes>
        </AuthContextProvider>
      </MemoryRouter>,
    )

    // No redirect response exists in this environment, so the honest outcome is "callback
    // finished but there is no session" - shown to the user, not bounced to /login.
    expect(await screen.findByRole('alert', undefined, { timeout: 8000 })).toBeInTheDocument()
    expect(screen.queryByText('login-landed')).not.toBeInTheDocument()
    expect(screen.queryByText('dashboard-landed')).not.toBeInTheDocument()

    expect(handleRedirectPromise).toHaveBeenCalled()
    for (const result of handleRedirectPromise.mock.results) {
      await expect(Promise.resolve(result.value)).resolves.toBeNull()
    }
  })
})
