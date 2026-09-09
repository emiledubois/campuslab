import { useEffect, useRef, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'

export function AuthCallbackPage() {
  const { handleRedirectCallback } = useAuth()
  const [status, setStatus] = useState<'pending' | 'done' | 'failed'>('pending')
  const started = useRef(false)

  useEffect(() => {
    if (started.current) {
      return
    }
    started.current = true

    handleRedirectCallback()
      .then(() => setStatus('done'))
      .catch(() => setStatus('failed'))
  }, [handleRedirectCallback])

  if (status === 'done') {
    return <Navigate to="/dashboard" replace />
  }
  if (status === 'failed') {
    return <Navigate to="/login" replace />
  }
  return null
}
