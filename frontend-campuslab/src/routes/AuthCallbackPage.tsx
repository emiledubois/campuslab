import { useEffect, useRef, useState } from 'react'
import { Link, Navigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

/**
 * /auth/callback is not behind ProtectedRoute, so this page can mount before the provider
 * has finished initialize(); MSAL rejects handleRedirectPromise() until then. Waiting for
 * isLoading keeps the callback strictly after initialization, and a failure is shown here
 * rather than silently bounced to /login.
 */
export function AuthCallbackPage() {
  const { handleRedirectCallback, isLoading, isAuthenticated } = useAuth()
  const [status, setStatus] = useState<'pending' | 'done' | 'failed'>('pending')
  const [failure, setFailure] = useState<string | null>(null)
  const started = useRef(false)

  useEffect(() => {
    if (isLoading || started.current) {
      return
    }
    started.current = true

    handleRedirectCallback()
      .then(() => setStatus('done'))
      .catch((error: unknown) => {
        console.error('El redirect de inicio de sesion fallo', error)
        setFailure(error instanceof Error ? error.message : 'Error desconocido.')
        setStatus('failed')
      })
  }, [handleRedirectCallback, isLoading])

  if (status === 'pending') {
    return null
  }
  if (status === 'done' && isAuthenticated) {
    return <Navigate to="/dashboard" replace />
  }

  return (
    <div className="flex flex-1 flex-col items-center justify-center gap-4 p-8">
      <p role="alert" className="max-w-md text-center text-red-600">
        {status === 'failed'
          ? `No se pudo completar el inicio de sesion: ${failure}`
          : 'El inicio de sesion termino, pero no quedo una sesion activa.'}
      </p>
      <Link to="/login" className="rounded bg-slate-900 px-4 py-2 text-white hover:bg-slate-700">
        Volver a iniciar sesion
      </Link>
    </div>
  )
}
