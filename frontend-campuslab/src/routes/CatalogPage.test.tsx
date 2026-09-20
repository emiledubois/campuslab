import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AuthContextProvider } from '../auth/AuthContext'
import { createFakeAuthProvider } from '../test/fakeAuthProvider'
import { CatalogPage } from './CatalogPage'

function meResponse(roles: string[]) {
  return new Response(
    JSON.stringify({
      sub: 'user-sub',
      oid: 'user-oid',
      username: 'user.test',
      email: 'user.test@campuslab.local',
      roles,
      issuer: 'https://login.microsoftonline.com/test-tenant/v2.0',
    }),
    { status: 200, headers: { 'Content-Type': 'application/json' } },
  )
}

const LAB_RESOURCE = {
  id: '5f9a5c1e-2a3b-4e10-9c2f-8b6d2b6b0a11',
  resourceType: 'LABORATORIO',
  name: 'Laboratorio de Redes 3',
  description: '20 estaciones',
  location: 'Edificio C',
  stock: null,
  cupo: 20,
  version: 0,
  createdAt: '2026-09-01T12:00:00Z',
  updatedAt: '2026-09-01T12:00:00Z',
}

function renderCatalogPage() {
  return render(
    <AuthContextProvider provider={createFakeAuthProvider({ authenticated: true, accessToken: 'token' })}>
      <CatalogPage />
    </AuthContextProvider>,
  )
}

describe('CatalogPage', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('renders the list plus create/update forms for admin.test', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.endsWith('/api/me')) {
          return meResponse(['ADMIN'])
        }
        if (url.endsWith('/api/catalog/resources')) {
          return new Response(JSON.stringify([LAB_RESOURCE]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        throw new Error(`unexpected fetch: ${url}`)
      }),
    )

    renderCatalogPage()

    expect(await screen.findByText('Laboratorio de Redes 3')).toBeInTheDocument()
    expect(screen.getByRole('form', { name: 'Crear recurso' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Editar' })).toBeInTheDocument()
  })

  it('renders the list with no create/update controls for tecnico.test', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.endsWith('/api/me')) {
          return meResponse(['TECNICO'])
        }
        if (url.endsWith('/api/catalog/resources')) {
          return new Response(JSON.stringify([LAB_RESOURCE]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        throw new Error(`unexpected fetch: ${url}`)
      }),
    )

    renderCatalogPage()

    expect(await screen.findByText('Laboratorio de Redes 3')).toBeInTheDocument()
    expect(screen.queryByRole('form', { name: 'Crear recurso' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Editar' })).not.toBeInTheDocument()
  })

  it('shows an access-denied message for estudiante.test and never calls GET /api/catalog/resources', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/me')) {
        return meResponse(['ESTUDIANTE'])
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderCatalogPage()

    expect(await screen.findByTestId('catalog-access-denied')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/api/catalog/resources'))).toBe(false)
  })

  it('shows an access-denied message for auditor.test and never calls GET /api/catalog/resources', async () => {
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('/api/me')) {
        return meResponse(['AUDITOR'])
      }
      throw new Error(`unexpected fetch: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    renderCatalogPage()

    expect(await screen.findByTestId('catalog-access-denied')).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([input]) => String(input).endsWith('/api/catalog/resources'))).toBe(false)
  })

  it('QA: shows a loading notice then an empty-state notice (not identical, not a silent blank list) for tecnico.test with no resources', async () => {
    let resolveFetch: (value: Response) => void = () => {}
    const pending = new Promise<Response>((resolve) => {
      resolveFetch = resolve
    })
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input)
        if (url.endsWith('/api/me')) {
          return meResponse(['TECNICO'])
        }
        if (url.endsWith('/api/catalog/resources')) {
          return pending
        }
        throw new Error(`unexpected fetch: ${url}`)
      }),
    )

    renderCatalogPage()

    // Loading and empty-but-loaded are structurally distinguishable data-testids.
    expect(await screen.findByTestId('async-state-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('async-state-empty')).not.toBeInTheDocument()

    resolveFetch(new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } }))

    expect(await screen.findByTestId('async-state-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('async-state-loading')).not.toBeInTheDocument()
    expect(screen.getByText('No hay recursos en el catalogo.')).toBeInTheDocument()
  })

  it('appends a newly created LABORATORIO to the list without a page reload', async () => {
    const created = { ...LAB_RESOURCE, id: 'new-id', name: 'Laboratorio nuevo' }
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        if (url.endsWith('/api/me')) {
          return meResponse(['ADMIN'])
        }
        if (url.endsWith('/api/catalog/resources') && (!init || init.method === undefined)) {
          return new Response(JSON.stringify([]), { status: 200, headers: { 'Content-Type': 'application/json' } })
        }
        if (url.endsWith('/api/catalog/resources') && init?.method === 'POST') {
          return new Response(JSON.stringify(created), {
            status: 201,
            headers: { 'Content-Type': 'application/json', Location: `/api/catalog/resources/${created.id}` },
          })
        }
        throw new Error(`unexpected fetch: ${url} ${init?.method}`)
      }),
    )

    const user = userEvent.setup()
    renderCatalogPage()

    const form = await screen.findByRole('form', { name: 'Crear recurso' })
    await user.type(within(form).getByLabelText('Nombre'), 'Laboratorio nuevo')
    await user.type(within(form).getByLabelText('Cupo'), '20')
    await user.click(within(form).getByRole('button', { name: 'Crear' }))

    expect(await screen.findByText('Laboratorio nuevo')).toBeInTheDocument()
  })

  it('surfaces a 409 on the update form as a reload-and-retry message, not a crash', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input)
        if (url.endsWith('/api/me')) {
          return meResponse(['ADMIN'])
        }
        if (url.endsWith('/api/catalog/resources')) {
          return new Response(JSON.stringify([LAB_RESOURCE]), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
          })
        }
        if (url.includes(`/api/catalog/resources/${LAB_RESOURCE.id}`) && init?.method === 'PUT') {
          return new Response(
            JSON.stringify({ detail: 'The resource was modified by someone else; reload and retry.' }),
            { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
          )
        }
        throw new Error(`unexpected fetch: ${url} ${init?.method}`)
      }),
    )

    const user = userEvent.setup()
    renderCatalogPage()

    await user.click(await screen.findByRole('button', { name: 'Editar' }))
    const form = screen.getByRole('form', { name: `Editar ${LAB_RESOURCE.name}` })
    await user.click(within(form).getByRole('button', { name: 'Guardar' }))

    expect(
      await screen.findByText('The resource was modified by someone else; reload and retry.'),
    ).toBeInTheDocument()
    expect(screen.getByText(LAB_RESOURCE.name)).toBeInTheDocument()
  })
})
