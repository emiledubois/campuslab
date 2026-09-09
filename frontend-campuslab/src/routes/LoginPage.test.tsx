import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { AuthContextProvider } from '../auth/AuthContext'
import { createFakeAuthProvider } from '../test/fakeAuthProvider'
import { LoginPage } from './LoginPage'

describe('LoginPage', () => {
  it('calls login when the button is clicked', async () => {
    const provider = createFakeAuthProvider({ authenticated: false })
    const loginSpy = vi.spyOn(provider, 'login')
    const user = userEvent.setup()
    render(
      <AuthContextProvider provider={provider}>
        <LoginPage />
      </AuthContextProvider>,
    )

    await user.click(await screen.findByRole('button', { name: 'Iniciar sesion' }))

    expect(loginSpy).toHaveBeenCalledTimes(1)
  })
})
