import type { ComponentPropsWithoutRef, ReactNode } from 'react'

export function PageContainer({ children }: { children: ReactNode }) {
  return <div className="flex flex-1 flex-col gap-6 p-8">{children}</div>
}

export function PageHeading({ children }: { children: ReactNode }) {
  return <h2 className="text-xl font-medium text-slate-900">{children}</h2>
}

/**
 * `...rest` (in practice `data-testid`) is forwarded so panels that already had a
 * stable test id (e.g. `reservas-por-hora-panel`) keep it after adopting this
 * component - the five pages' own tests key off those ids.
 */
export function Card({
  children,
  className = '',
  ...rest
}: { children: ReactNode; className?: string } & ComponentPropsWithoutRef<'div'>) {
  return (
    <div className={`rounded border border-slate-200 p-4 ${className}`} {...rest}>
      {children}
    </div>
  )
}
