/**
 * Single source of truth for which roles can reach which screen, sourced verbatim
 * from docs/CASO2_REQUISITOS.md §6. Consumed by both NavBar (link visibility, UX
 * only) and each page's own unchanged canView gate (the actual access control lives
 * server-side in the BFF and each domain service - see this module's callers'
 * docstrings).
 */
export type Role = 'ADMIN' | 'TECNICO' | 'ESTUDIANTE' | 'AUDITOR'

export function canViewBookings(roles: string[]): boolean {
  return roles.includes('ADMIN') || roles.includes('TECNICO') || roles.includes('ESTUDIANTE')
}

export function canViewCatalog(roles: string[]): boolean {
  return roles.includes('ADMIN') || roles.includes('TECNICO')
}

export function canViewReports(roles: string[]): boolean {
  return roles.includes('ADMIN')
}

export function canViewAudit(roles: string[]): boolean {
  return roles.includes('ADMIN') || roles.includes('AUDITOR')
}

export interface NavLinkConfig {
  path: string
  label: string
  canView: (roles: string[]) => boolean
}

export const NAV_LINKS: NavLinkConfig[] = [
  { path: '/dashboard', label: 'Dashboard', canView: () => true },
  { path: '/bookings', label: 'Reservas', canView: canViewBookings },
  { path: '/catalog', label: 'Catalogo', canView: canViewCatalog },
  { path: '/reports', label: 'Reportes', canView: canViewReports },
  { path: '/audit', label: 'Auditoria', canView: canViewAudit },
]
