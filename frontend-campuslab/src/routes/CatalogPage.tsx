import { useEffect, useState, type FormEvent } from 'react'
import { apiFetch } from '../api/httpClient'
import { useMe } from '../hooks/useMe'

type ResourceType = 'LABORATORIO' | 'EQUIPO' | 'INSUMO'

interface CatalogResource {
  id: string
  resourceType: ResourceType
  name: string
  description: string | null
  location: string | null
  stock: number | null
  cupo: number | null
  version: number
  createdAt: string
  updatedAt: string
}

const RESOURCE_TYPES: ResourceType[] = ['LABORATORIO', 'EQUIPO', 'INSUMO']
const CONFLICT_MESSAGE = 'The resource was modified by someone else; reload and retry.'

function usesCupo(resourceType: ResourceType): boolean {
  return resourceType === 'LABORATORIO'
}

/**
 * Role gating here is UX only - GET/POST/PUT are independently re-enforced by the
 * BFF and by catalog itself (see acceptance criteria 5-8), so a user who bypassed
 * this client-side check would still get 403 from the server, never real data.
 */
export function CatalogPage() {
  const { me, isLoading: meLoading } = useMe()
  const roles = me?.roles ?? []
  const canView = roles.includes('ADMIN') || roles.includes('TECNICO')
  const isAdmin = roles.includes('ADMIN')

  const [resources, setResources] = useState<CatalogResource[]>([])
  const [listError, setListError] = useState<string | null>(null)

  useEffect(() => {
    if (meLoading || !canView) {
      return
    }
    let cancelled = false

    apiFetch('/api/catalog/resources')
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`GET /api/catalog/resources failed with status ${response.status}`)
        }
        return (await response.json()) as CatalogResource[]
      })
      .then((data) => {
        if (!cancelled) {
          setResources(data)
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setListError(err instanceof Error ? err.message : 'No se pudo cargar el catalogo.')
        }
      })

    return () => {
      cancelled = true
    }
  }, [meLoading, canView])

  if (meLoading) {
    return null
  }

  if (!canView) {
    return (
      <div className="flex flex-1 flex-col p-8">
        <p data-testid="catalog-access-denied" className="text-red-600">
          No tienes acceso al catalogo.
        </p>
      </div>
    )
  }

  return (
    <div className="flex flex-1 flex-col gap-6 p-8">
      <h2 className="text-xl font-medium text-slate-900">Catalogo</h2>

      {listError && <p className="text-red-600">{listError}</p>}

      {isAdmin && (
        <CreateResourceForm onCreated={(created) => setResources((current) => [...current, created])} />
      )}

      <ul data-testid="catalog-list" className="flex flex-col gap-3">
        {resources.map((resource) => (
          <CatalogResourceRow
            key={resource.id}
            resource={resource}
            isAdmin={isAdmin}
            onUpdated={(updated) =>
              setResources((current) => current.map((existing) => (existing.id === updated.id ? updated : existing)))
            }
          />
        ))}
      </ul>
    </div>
  )
}

function CreateResourceForm({ onCreated }: { onCreated: (resource: CatalogResource) => void }) {
  const [resourceType, setResourceType] = useState<ResourceType>('LABORATORIO')
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [location, setLocation] = useState('')
  const [quantity, setQuantity] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)

    const body = {
      resourceType,
      name,
      description: description || null,
      location: location || null,
      stock: usesCupo(resourceType) ? null : Number(quantity),
      cupo: usesCupo(resourceType) ? Number(quantity) : null,
    }

    try {
      const response = await apiFetch('/api/catalog/resources', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (!response.ok) {
        throw new Error(`POST /api/catalog/resources failed with status ${response.status}`)
      }
      const created = (await response.json()) as CatalogResource
      onCreated(created)
      setName('')
      setDescription('')
      setLocation('')
      setQuantity('')
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo crear el recurso.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      aria-label="Crear recurso"
      className="flex flex-col gap-3 rounded border border-slate-200 p-4"
    >
      <label className="flex flex-col gap-1 text-sm">
        Tipo
        <select value={resourceType} onChange={(event) => setResourceType(event.target.value as ResourceType)}>
          {RESOURCE_TYPES.map((type) => (
            <option key={type} value={type}>
              {type}
            </option>
          ))}
        </select>
      </label>
      <label className="flex flex-col gap-1 text-sm">
        Nombre
        <input value={name} onChange={(event) => setName(event.target.value)} required />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        Descripcion
        <input value={description} onChange={(event) => setDescription(event.target.value)} />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        Ubicacion
        <input value={location} onChange={(event) => setLocation(event.target.value)} />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        {usesCupo(resourceType) ? 'Cupo' : 'Stock'}
        <input
          type="number"
          min={0}
          value={quantity}
          onChange={(event) => setQuantity(event.target.value)}
          required
        />
      </label>
      {error && <p className="text-red-600">{error}</p>}
      <button
        type="submit"
        disabled={submitting}
        className="w-fit rounded bg-slate-900 px-3 py-1.5 text-white hover:bg-slate-700"
      >
        Crear
      </button>
    </form>
  )
}

function CatalogResourceRow({
  resource,
  isAdmin,
  onUpdated,
}: {
  resource: CatalogResource
  isAdmin: boolean
  onUpdated: (resource: CatalogResource) => void
}) {
  const [editing, setEditing] = useState(false)

  return (
    <li data-testid={`catalog-item-${resource.id}`} className="rounded border border-slate-200 p-4">
      <div className="flex items-center justify-between">
        <div>
          <p className="font-medium">{resource.name}</p>
          <p className="text-sm text-slate-500">
            {resource.resourceType} - {usesCupo(resource.resourceType) ? `cupo ${resource.cupo}` : `stock ${resource.stock}`}
          </p>
        </div>
        {isAdmin && (
          <button
            type="button"
            onClick={() => setEditing((current) => !current)}
            className="text-sm text-slate-700 underline"
          >
            {editing ? 'Cancelar' : 'Editar'}
          </button>
        )}
      </div>
      {isAdmin && editing && (
        <UpdateResourceForm
          resource={resource}
          onUpdated={(updated) => {
            onUpdated(updated)
            setEditing(false)
          }}
        />
      )}
    </li>
  )
}

function UpdateResourceForm({
  resource,
  onUpdated,
}: {
  resource: CatalogResource
  onUpdated: (resource: CatalogResource) => void
}) {
  const [name, setName] = useState(resource.name)
  const [description, setDescription] = useState(resource.description ?? '')
  const [location, setLocation] = useState(resource.location ?? '')
  const [quantity, setQuantity] = useState(
    String((usesCupo(resource.resourceType) ? resource.cupo : resource.stock) ?? ''),
  )
  const [error, setError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setError(null)
    setSubmitting(true)

    const body = {
      name,
      description: description || null,
      location: location || null,
      stock: usesCupo(resource.resourceType) ? null : Number(quantity),
      cupo: usesCupo(resource.resourceType) ? Number(quantity) : null,
      // Hidden field re-submitted from the last fetched value, never shown to the user
      // as a raw number (see design doc's Open Question 4) - just the optimistic-lock
      // token the server gave us on the last GET/POST/PUT response for this resource.
      version: resource.version,
    }

    try {
      const response = await apiFetch(`/api/catalog/resources/${resource.id}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      if (response.status === 409) {
        throw new Error(CONFLICT_MESSAGE)
      }
      if (!response.ok) {
        throw new Error(`PUT /api/catalog/resources/${resource.id} failed with status ${response.status}`)
      }
      const updated = (await response.json()) as CatalogResource
      onUpdated(updated)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'No se pudo actualizar el recurso.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form
      onSubmit={handleSubmit}
      aria-label={`Editar ${resource.name}`}
      className="mt-3 flex flex-col gap-2 border-t border-slate-100 pt-3"
    >
      <label className="flex flex-col gap-1 text-sm">
        Nombre
        <input value={name} onChange={(event) => setName(event.target.value)} required />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        Descripcion
        <input value={description} onChange={(event) => setDescription(event.target.value)} />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        Ubicacion
        <input value={location} onChange={(event) => setLocation(event.target.value)} />
      </label>
      <label className="flex flex-col gap-1 text-sm">
        {usesCupo(resource.resourceType) ? 'Cupo' : 'Stock'}
        <input
          type="number"
          min={0}
          value={quantity}
          onChange={(event) => setQuantity(event.target.value)}
          required
        />
      </label>
      {error && <p className="text-red-600">{error}</p>}
      <button
        type="submit"
        disabled={submitting}
        className="w-fit rounded border border-slate-300 px-3 py-1.5 hover:bg-slate-100"
      >
        Guardar
      </button>
    </form>
  )
}
