import { NavLink } from 'react-router-dom'
import { useAuth } from '../auth/useAuth'
import { useMe } from '../hooks/useMe'
import { NAV_LINKS } from '../routes/roleAccess'

/**
 * Link visibility is UX only, never an access control - see roleAccess.ts and each
 * page's own unchanged canView gate, which is what actually renders "No tienes
 * acceso a..." if a hidden route is reached directly. The real protection is
 * entirely server-side (BFF + domain services), unaffected by this component.
 *
 * useMe() starts with isLoading: true and roles: []; NAV_LINKS' predicates against
 * an empty roles array already resolve to "only Dashboard", so there is no separate
 * loading branch needed here to avoid a flash of role-gated links.
 */
export function NavBar() {
  const { logout } = useAuth()
  const { me } = useMe()
  const roles = me?.roles ?? []
  const links = NAV_LINKS.filter((link) => link.canView(roles))

  return (
    <nav aria-label="Navegacion principal" className="flex items-center gap-4">
      <ul className="flex items-center gap-4 text-sm">
        {links.map((link) => (
          <li key={link.path}>
            <NavLink
              to={link.path}
              data-testid={`nav-link-${link.path.slice(1)}`}
              className={({ isActive }) =>
                isActive ? 'font-semibold text-slate-900 underline' : 'text-slate-500 hover:text-slate-700'
              }
            >
              {link.label}
            </NavLink>
          </li>
        ))}
      </ul>
      <button
        type="button"
        onClick={() => {
          void logout()
        }}
        className="rounded border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-100"
      >
        Cerrar sesion
      </button>
    </nav>
  )
}
