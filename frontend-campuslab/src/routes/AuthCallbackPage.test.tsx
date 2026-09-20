import { act, render, screen } from '@testing-library/react'
import { StrictMode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AuthContextProvider } from '../auth/AuthContext'
import { createFakeAuthProvider } from '../test/fakeAuthProvider'
import { AuthCallbackPage } from './AuthCallbackPage'

function renderCallback(provider = createFakeAuthProvider({ authenticated: false }), options: { strict?: boolean } = {}) {
  const tree = (
    <MemoryRouter initialEntries={['/auth/callback']}>
      <AuthContextProvider provider={provider}>
        <Routes>
          <Route path="/auth/callback" element={<AuthCallbackPage />} />
          <Route path="/dashboard" element={<div>dashboard-landed</div>} />
          <Route path="/login" element={<div>login-landed</div>} />
        </Routes>
      </AuthContextProvider>
    </MemoryRouter>
  )
  return render(options.strict ? <StrictMode>{tree}</StrictMode> : tree)
}

describe('AuthCallbackPage', () => {
  it('navigates to /dashboard after a successful redirect callback', async () => {
    renderCallback()

    expect(await screen.findByText('dashboard-landed')).toBeInTheDocument()
  })

  it('shows the failure instead of redirecting silently to /login when the redirect callback fails', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const provider = createFakeAuthProvider({ authenticated: false })
    provider.handleRedirectCallback = () => Promise.reject(new Error('AADSTS50011: redirect uri mismatch'))

    renderCallback(provider)

    expect(await screen.findByRole('alert')).toHaveTextContent('AADSTS50011: redirect uri mismatch')
    expect(screen.getByRole('link', { name: /volver a iniciar sesion/i })).toHaveAttribute('href', '/login')
    expect(screen.queryByText('login-landed')).not.toBeInTheDocument()
    expect(consoleError).toHaveBeenCalled()
    consoleError.mockRestore()
  })

  it('shows a message instead of bouncing through /dashboard when the callback completes without a session', async () => {
    const provider = createFakeAuthProvider({ authenticated: false })
    provider.handleRedirectCallback = () => Promise.resolve()

    renderCallback(provider)

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.queryByText('dashboard-landed')).not.toBeInTheDocument()
    expect(screen.queryByText('login-landed')).not.toBeInTheDocument()
  })

  it('does not call handleRedirectCallback or navigate while the provider is still initializing', async () => {
    const provider = createFakeAuthProvider({ authenticated: false })
    let finishInitialize: () => void = () => {}
    provider.initialize = () =>
      new Promise<void>((resolve) => {
        finishInitialize = resolve
      })
    const handleRedirectCallback = vi.fn(provider.handleRedirectCallback.bind(provider))
    provider.handleRedirectCallback = handleRedirectCallback

    renderCallback(provider)
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20))
    })

    expect(handleRedirectCallback).not.toHaveBeenCalled()
    expect(screen.queryByText('dashboard-landed')).not.toBeInTheDocument()
    expect(screen.queryByText('login-landed')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    await act(async () => {
      finishInitialize()
    })

    expect(await screen.findByText('dashboard-landed')).toBeInTheDocument()
    expect(handleRedirectCallback).toHaveBeenCalledTimes(1)
  })

  it('calls handleRedirectCallback exactly once under StrictMode', async () => {
    const provider = createFakeAuthProvider({ authenticated: false })
    const handleRedirectCallback = vi.fn(provider.handleRedirectCallback.bind(provider))
    provider.handleRedirectCallback = handleRedirectCallback

    renderCallback(provider, { strict: true })

    expect(await screen.findByText('dashboard-landed')).toBeInTheDocument()
    expect(handleRedirectCallback).toHaveBeenCalledTimes(1)
  })
})
