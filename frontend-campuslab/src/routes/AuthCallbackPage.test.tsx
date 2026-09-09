import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { AuthContextProvider } from '../auth/AuthContext'
import { createFakeAuthProvider } from '../test/fakeAuthProvider'
import { AuthCallbackPage } from './AuthCallbackPage'

function renderCallback(provider = createFakeAuthProvider({ authenticated: false })) {
  return render(
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
}

describe('AuthCallbackPage', () => {
  it('navigates to /dashboard after a successful redirect callback', async () => {
    renderCallback()

    expect(await screen.findByText('dashboard-landed')).toBeInTheDocument()
  })

  it('navigates to /login when the redirect callback fails', async () => {
    const provider = createFakeAuthProvider({ authenticated: false })
    provider.handleRedirectCallback = () => Promise.reject(new Error('invalid state'))

    renderCallback(provider)

    expect(await screen.findByText('login-landed')).toBeInTheDocument()
  })
})
