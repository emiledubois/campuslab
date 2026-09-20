export type AsyncStateKind = 'loading' | 'empty' | 'error'

const STYLES: Record<AsyncStateKind, string> = {
  loading: 'border-slate-200 bg-slate-50 text-slate-600',
  empty: 'border-slate-200 bg-white text-slate-500',
  error: 'border-red-200 bg-red-50 text-red-700',
}

/**
 * One reusable loading/empty/error notice shared by all five pages, replacing the
 * bespoke inline `<p className="text-red-600">` / silent-empty-list / no-loading
 * patterns that made a loading page and an empty-but-loaded page indistinguishable.
 */
export function AsyncStateNotice({
  kind,
  message,
  testId,
}: {
  kind: AsyncStateKind
  message: string
  testId?: string
}) {
  return (
    <div
      role={kind === 'error' ? 'alert' : 'status'}
      data-testid={testId ?? `async-state-${kind}`}
      className={`flex items-center gap-2 rounded border p-4 text-sm ${STYLES[kind]}`}
    >
      {kind === 'loading' && (
        <span className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-slate-900" />
      )}
      <span>{message}</span>
    </div>
  )
}
