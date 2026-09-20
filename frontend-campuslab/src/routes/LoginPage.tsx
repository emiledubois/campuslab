import { useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { getActiveAuthProvider } from '../auth/authRegistry'
import { DEMO_IDENTITIES, DemoSessionProvider, isDemoAuthEnabled, type DemoIdentity } from '../auth/DemoSessionProvider'

export function LoginPage() {
  const { login } = useAuth()
  const [error, setError] = useState<string | null>(null)
  const [signedIn, setSignedIn] = useState(false)

  /**
   * Demo mode (docs/DEMO_LOCAL.md): there is no identity provider to redirect to, so the
   * user picks which seeded identity to sign in as and the token comes from mock-jwks.
   * Everything after this point - bearer attachment, BFF validation, role and ownership
   * checks - is the same code path a real Entra login would take.
   */
  const handleDemoLogin = async (identity: DemoIdentity) => {
    const provider = getActiveAuthProvider()
    if (!(provider instanceof DemoSessionProvider)) {
      setError('El modo demo no esta activo. Revisa VITE_AUTH_MODE en frontend-campuslab/.env')
      return
    }
    try {
      setError(null)
      await provider.loginAs(identity)
      setSignedIn(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo iniciar sesion en modo demo.')
    }
  }

  if (isDemoAuthEnabled()) {
    // <Navigate> rather than useNavigate(): the hook would run on every render of this
    // component, including the Entra path whose own test renders LoginPage outside a
    // Router. This element is only ever created in demo mode.
    if (signedIn) {
      return <Navigate to="/dashboard" replace />
    }

    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-4 p-8">
        <p className="text-slate-600">Modo demo local: elige con que identidad entrar.</p>
        <div className="flex flex-col gap-2">
          {DEMO_IDENTITIES.map((identity) => (
            <button
              key={identity.key}
              type="button"
              onClick={() => {
                void handleDemoLogin(identity)
              }}
              className="rounded bg-slate-900 px-4 py-2 text-white hover:bg-slate-700"
            >
              {identity.label}
            </button>
          ))}
        </div>
        {error ? <p className="max-w-md text-center text-sm text-red-600">{error}</p> : null}
        <p className="max-w-md text-center text-xs text-slate-400">
          Tokens emitidos por el doble de pruebas mock-jwks. Sin tenant de Entra disponible.
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-4 p-8">
      <p className="text-slate-600">Inicia sesion para continuar.</p>
      <button
        type="button"
        onClick={() => {
          void login()
        }}
        className="rounded bg-slate-900 px-4 py-2 text-white hover:bg-slate-700"
      >
        Iniciar sesion
      </button>
    </div>
  )
}
