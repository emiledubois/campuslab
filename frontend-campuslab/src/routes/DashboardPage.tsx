import { AsyncStateNotice } from '../components/AsyncStateNotice'
import { PageContainer, PageHeading } from '../components/Page'
import { useMe } from '../hooks/useMe'

export function DashboardPage() {
  const { me, error, isLoading } = useMe()

  return (
    <PageContainer>
      <PageHeading>Dashboard</PageHeading>

      {isLoading && <AsyncStateNotice kind="loading" message="Cargando..." />}
      {!isLoading && error && <AsyncStateNotice kind="error" message={error} />}

      {!isLoading && me && (
        <dl className="grid max-w-md grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
          <dt className="font-medium text-slate-500">Usuario</dt>
          <dd data-testid="me-username">{me.username}</dd>
          <dt className="font-medium text-slate-500">Email</dt>
          <dd data-testid="me-email">{me.email}</dd>
          <dt className="font-medium text-slate-500">Roles</dt>
          <dd data-testid="me-roles">{me.roles.join(', ')}</dd>
          <dt className="font-medium text-slate-500">Emisor</dt>
          <dd data-testid="me-issuer">{me.issuer}</dd>
        </dl>
      )}
    </PageContainer>
  )
}
