import { useAuth } from '../auth/useAuth'

export function LoginPage() {
  const { login } = useAuth()

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
