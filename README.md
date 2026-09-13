# CampusLab

Plataforma de reserva de laboratorios y equipos academicos para una red de 20 laboratorios de educacion superior: reserva de salas/equipos por web, control de stock de insumos, notificaciones (email/push al estudiante, ticket de preparacion al tecnico), un panel de KPIs de ocupacion en tiempo real y auditoria de eventos academicos (quien solicito, aprobo, entrego o recibio de vuelta un equipo).

Dos desviaciones deliberadas respecto de un enunciado basado en Angular/Oracle: **React** en lugar de Angular, y **PostgreSQL** en lugar de Oracle.

## Estado actual

Este repositorio esta en etapa de **scaffolding**: los ocho microservicios Spring Boot, el frontend React y la infraestructura Docker Compose existen como esqueletos verificados (compilan, arrancan, exponen `/actuator/health`), pero **ninguna funcionalidad de negocio esta implementada todavia**.

## Roles

| Rol | Responsabilidad |
|---|---|
| `ADMIN` | Administra el catalogo de labs/equipos y ve KPIs de ocupacion. |
| `TECNICO` | Aprueba reservas, prepara la sala y registra devoluciones. |
| `ESTUDIANTE` | Solicita y sigue sus propias reservas. |
| `AUDITOR` | Consulta el timeline de auditoria. Solo lectura. |

## Regla de estado de una reserva

`SOLICITADA → APROBADA → EN_PREPARACION → EN_USO → DEVUELTA | CANCELADA`, siempre validada en el servidor. No se puede llegar a `EN_USO` sin haber pasado por `APROBADA`. El stock del catalogo baja al aprobar, no al solicitar.

## Stack

| Capa | Eleccion |
|---|---|
| Backend | Java 21, Spring Boot 3.5.16, Maven wrapper por servicio |
| Persistencia | PostgreSQL, un contenedor y una base de datos por servicio, migraciones Flyway |
| Frontend | React 19, Vite, TypeScript, Tailwind CSS v4, `@azure/msal-browser` + `@azure/msal-react` |
| Identidad | OIDC — Entra ID (Azure AD) es el unico proveedor, en todo entorno (sin proveedor local); tests automatizados firman sus propios JWT contra un JWKS simulado |
| Edge | AWS API Gateway (HTTP API, JWT authorizer) en despliegue; el BFF revalida el token igual |
| Mensajeria | RabbitMQ (colas de trabajo + DLQ), Kafka + Zookeeper (streaming de eventos) |
| Despliegue | Docker Compose, perfiles `local` y `deploy` |
| Tests | JUnit 5 + Mockito (backend); Vitest + React Testing Library (frontend) |

## Estructura del repositorio

```
frontend-campuslab/         React + Vite + TS + Tailwind
ms-campuslab-bff/           unico punto de entrada publico, Spring Security
ms-campuslab-bookings/      reservas + maquina de estados, Postgres propio
ms-campuslab-catalog/       labs/equipos/insumos + stock, Postgres propio
ms-campuslab-notify/        consumidor RabbitMQ, sin DB
ms-campuslab-report/        consumidor Kafka + lecturas de KPIs, Postgres propio
ms-campuslab-audit/         consumidor Kafka + lecturas de timeline, Postgres propio
ms-campuslab-mq-admin/      declara/inspecciona topologia RabbitMQ, sin DB
ms-campuslab-kafka-admin/   declara/inspecciona topicos Kafka, sin DB
infra/
  apps/compose.yml          los 8 servicios + sus Postgres
  mq/compose.yml            RabbitMQ + Management UI
  kafka/compose.yml         Zookeeper + Kafka + Kafka UI
```

El camino de llamada es siempre: navegador → API Gateway → `ms-campuslab-bff` → microservicio de dominio. El frontend nunca llama directo a un microservicio de dominio; ningun microservicio de dominio se expone publicamente en compose — solo la BFF publica un puerto al host.

## Requisitos previos

- **Java 21** y **Docker + Docker Compose** (probado con Docker 29.7 / Compose v5).
- **Red compartida de Docker (una sola vez)**: los tres archivos de compose (`infra/mq`,
  `infra/kafka`, `infra/apps`) declaran `campuslab-net` como `external: true` — sin ella,
  `mq-admin`/`notify`/`bookings` no podrian resolver `rabbitmq`/`kafka` por nombre de host
  (slice 5, `docs/designs/messaging-notify.md` §7 A05). `scripts/cold-start.sh` (ver mas
  abajo) la crea automaticamente si todavia no existe, pero tambien se puede crear a mano
  una sola vez por maquina/host:
  ```bash
  docker network create campuslab-net
  ```
  No la crea ningun `docker compose up`/`down` — sigue en pie entre reinicios del stack.
- **Node.js** para el frontend. *Nota:* en esta maquina el `node` del sistema esta roto (falta `libada.so.3`, aparentemente por una actualizacion parcial de paquetes de Arch). Se instalo Node LTS de forma aislada via `nvm` en `~/.nvm` (sin tocar paquetes del sistema); para usarlo:
  ```bash
  source ~/.nvm/nvm.sh
  nvm use --lts
  ```
  Si tu maquina tiene un `node` funcional del sistema, ignora este paso.

## Arrancar todo localmente

Cada paso fue efectivamente levantado y verificado durante el scaffolding (no son instrucciones sin probar).

1. **Copiar variables de entorno**: `cp .env.example .env` y completar los valores (usuarios/contraseñas de desarrollo, nunca reales/productivos). Docker Compose los toma automaticamente si `.env` esta en la raiz del repo o se referencia con `--env-file`.

2. **Identidad (Entra ID)** — no hay proveedor de identidad local (`docs/DECISIONES_PROFESOR.md` #6): Entra ID es el unico emisor, en todo entorno, incluido el desarrollo local. No hay nada que levantar aqui; `OIDC_ISSUER_URI`/`OIDC_AUDIENCE` apuntan al tenant real de Entra (ver `docs/ENTRA_SETUP.md`). Los tests automatizados firman sus propios JWT contra un JWKS simulado (no llaman a Entra) — ver `docs/designs/entra-migration.md`.

3. **RabbitMQ + Kafka + los ocho microservicios + sus bases de datos, en un solo comando**
   (`docs/designs/demo-readiness.md` Decision 1):
   ```bash
   ./scripts/cold-start.sh
   ```
   Equivalente a (y exactamente lo que el script ejecuta):
   ```bash
   docker compose --env-file .env \
     -f infra/mq/compose.yml \
     -f infra/kafka/compose.yml \
     -f infra/apps/compose.yml \
     --profile local up -d --build
   ```
   Compose fusiona los tres archivos en **un solo proyecto**, asi que `depends_on:
   condition: service_healthy` funciona entre archivos exactamente igual que dentro de
   uno solo — `mq-admin`/`kafka-admin` (los unicos dueños de la topologia RabbitMQ/Kafka)
   dependen de `rabbitmq`/`kafka` estar sanos; `notify`/`bookings` dependen ademas de que
   `mq-admin`/`kafka-admin` esten sanos; `audit`/`report` dependen de `kafka-admin`; `bff`
   depende de todo lo anterior. Cada uno de los dos servicios admin expone un
   `TopologyHealthIndicator` propio (`GET /actuator/health`, `components.topology`) que
   reporta `DOWN` hasta que su propia logica de declarar+verificar topologia (slices 5/6,
   sin cambios) haya terminado con exito al menos una vez — asi "Compose dice que
   mq-admin/kafka-admin estan sanos" significa realmente "la topologia ya existe", no solo
   "la JVM arranco". Esto es lo que cierra, de forma estructural, la carrera que el
   informe de QA de la slice 5 habia señalado (tres invocaciones de compose separadas, sin
   `depends_on` entre ellas) — ver `docs/designs/demo-readiness.md` para la evidencia real
   (10 ciclos en frio reproduciendo la carrera con la topologia vieja, 10 ciclos mas
   probando que ya no ocurre con esta).

   RabbitMQ Management UI en `http://localhost:15672`, Kafka UI en `http://localhost:8082`.
   Solo `ms-campuslab-bff` publica un puerto de aplicacion al host (`8080`) — es el unico
   punto de entrada publico. Ningun otro microservicio (bookings, catalog, notify, report,
   audit, mq-admin, kafka-admin) es alcanzable desde el host; solo entre contenedores de la
   misma red de compose. Verificar: `curl http://localhost:8080/actuator/health` debe
   devolver `{"status":"UP"}`.

   El perfil `deploy` (no usado por `cold-start.sh`, que siempre usa `local`) levanta la
   topologia real del caso: RabbitMQ 2 nodos, Zookeeper 3 nodos + Kafka 3 brokers,
   replicacion real — sin publicar ninguna UI de administracion.

4. **Datos de demo (opcional, recomendado antes de una presentacion; es un flujo local
   distinto del arranque real del paso 3, nunca combinado con el)**:

   Este flujo crea reservas *a traves de la API real* (`POST /api/bookings` /
   `PUT /api/bookings/{id}/status` via el BFF) para cubrir los seis estados del ciclo de
   vida (`SOLICITADA`, `APROBADA`, `EN_PREPARACION`, `EN_USO`, `DEVUELTA`, `CANCELADA`),
   asi que `/audit` y `/reports` tengan datos reales derivados del stream de eventos, no
   solo `/bookings`. Para autenticar esas llamadas sin depender de un login real contra
   Entra (ROPC esta descartado — incompatible con MFA), se usa un doble de pruebas
   JWKS/discovery minimo y autocontenido, definido en `infra/testing/` (compose propio,
   **nunca** fusionado a `scripts/cold-start.sh` — ver `docs/designs/demo-readiness.md`
   §9 AC8) que firma JWT locales con el mismo formato de claims que Entra. Ver
   `docs/designs/demo-readiness.md` Decision 2 para el analisis completo de por que esto
   no viola `docs/DECISIONES_PROFESOR.md` #6.

   Requiere que, **solo para esta sesion de siembra/ensayo local**, `.env` tenga
   `OIDC_ISSUER_URI`/`OIDC_AUDIENCE` apuntando al doble de pruebas en vez de al tenant
   real de Entra — los valores exactos son los que `infra/testing/compose.yml` fija por
   defecto para ese doble de pruebas (variables `MOCK_JWKS_ISSUER`/`MOCK_JWKS_AUDIENCE`
   en ese archivo; el script de abajo falla con un mensaje explicito si `.env` no
   coincide). Es una configuracion distinta de la usada para el login real via MSAL en
   la presentacion, que siempre apunta al tenant real, sin excepcion. Un unico comando
   ejecuta la secuencia correcta de principio a fin:
   ```bash
   ./scripts/seed-demo-data.sh
   ```
   Ese script (i) arranca el doble de pruebas JWKS/discovery primero y espera a que
   responda; (ii) recien entonces levanta el stack completo (`scripts/cold-start.sh`) —
   arrancarlo antes de tener ese doble de pruebas arriba haria que cada servicio con
   OIDC configurado fallara al iniciar (descubrimiento OIDC eager contra un host
   inexistente); (iii) corre `scripts/seed.sh` (SQL directo e idempotente contra
   `catalog-db`, mas las reservas via API real); (iv) corre `scripts/verify-seed.sh`
   (relee todo lo sembrado a traves del BFF real, mintiendo sus propios tokens
   `ADMIN`/`TECNICO`/`ESTUDIANTE`/`AUDITOR`). El doble de pruebas se mantiene como un
   unico proceso, sin reinicios intermedios, durante las cuatro etapas — reiniciarlo a
   mitad de sesion regeneraria su clave RSA bajo el mismo `kid` y las firmas ya
   cacheadas por los servicios dejarian de validar — y se apaga al terminar el script
   (exito o error).

5. **Frontend**:
   ```bash
   cd frontend-campuslab
   npm install
   npm run dev
   ```
   Sirve en `http://localhost:5173` (coincide con el `redirectUri` configurado en el registro de aplicacion SPA de Entra ID, ver `docs/ENTRA_SETUP.md`).

### Tiempo de arranque en frío

Medido por `scripts/measure-cold-boot.sh` (`docs/designs/demo-readiness.md` Decision 3):
arranque = el instante en que se invoca `scripts/cold-start.sh`, inmediatamente precedido
por un `down -v` real (contenedores **y** volúmenes con nombre) sobre los tres archivos de
compose, de modo que Postgres vuelve a correr todas las migraciones Flyway desde cero y
RabbitMQ/Kafka recrean su topología desde nada. Fin = el primer instante en que **ambas**
condiciones son verdaderas: (1) todos los contenedores que el `up` fusionado arrancó
reportan `healthy` (o `running`, para el único sin healthcheck hoy, `kafka-ui`); **y** (2)
un round-trip autenticado real tiene éxito de punta a punta: `GET /api/me` contra el puerto
publicado de la BFF, con un token `ESTUDIANTE` firmado por el doble de pruebas JWKS de
`infra/testing/`, devuelve `200` con el `oid`/`roles` de ese token reflejados
correctamente. Se registra al conectar el stack local (perfil `local`) a esta misma
configuración de prueba (nunca al tenant real de Entra) — ver ese script para el detalle
exacto. Tabla append-only: cada nueva medición se agrega, nunca reemplaza una anterior.

| Fecha | Máquina | Segundos | Commit |
|---|---|---|---|
| 2026-09-11 | Linux (CachyOS), 88 vCPU, 46 GiB RAM, Docker 29.7.2 / Compose v5.5.0, imágenes ya construidas en caché local (`docker compose ... up -d --build` con capas cacheadas) | 67.2 | e30a60b |

## Correr un microservicio individual fuera de Docker

```bash
cd ms-campuslab-catalog
SERVER_PORT=8080 DB_URL=jdbc:postgresql://localhost:5432/catalog DB_USERNAME=catalog DB_PASSWORD=catalog \
  OIDC_ISSUER_URI=https://login.microsoftonline.com/<TENANT_ID>/v2.0 OIDC_AUDIENCE=api://<API_CLIENT_ID> \
  ./mvnw spring-boot:run
```
(Los servicios sin persistencia — bff, notify, mq-admin, kafka-admin — solo necesitan `SERVER_PORT`, ninguna variable de base de datos. Desde la slice 2, `ms-campuslab-catalog` tambien valida su propio JWT — ver "defensa en profundidad" en `docs/designs/catalog.md` — asi que necesita las mismas dos variables `OIDC_*` que la BFF.)

**Nota (slice 2, catalogo):** si se corre `ms-campuslab-bff` directo en el host (no via `infra/apps/compose.yml`) y se quiere que llegue a un `ms-campuslab-catalog` tambien corriendo en el host, `catalog` no es un hostname resoluble fuera de la red de compose — hay que apuntar `CATALOG_SERVICE_URL` a `http://localhost:<puerto>` con catalog arrancado en un puerto distinto al 8080 de la BFF, por ejemplo:
```bash
# terminal 1
cd ms-campuslab-catalog
SERVER_PORT=8082 DB_URL=jdbc:postgresql://localhost:5432/catalog DB_USERNAME=catalog DB_PASSWORD=catalog \
  OIDC_ISSUER_URI=... OIDC_AUDIENCE=api://<API_CLIENT_ID> ./mvnw spring-boot:run

# terminal 2
cd ms-campuslab-bff
CATALOG_SERVICE_URL=http://localhost:8082 OIDC_ISSUER_URI=... OIDC_AUDIENCE=api://<API_CLIENT_ID> \
  CORS_ALLOWED_ORIGINS=http://localhost:5173 ./mvnw spring-boot:run
```
Bajo `infra/apps/compose.yml` no hace falta nada de esto: `CATALOG_SERVICE_URL` ya viene fijado ahi a `http://catalog:8080` (el hostname interno de compose), igual que `DB_URL` para los servicios con base de datos.

## Verificacion de cada pieza

- Los 8 servicios: `./mvnw -q compile` exitoso y arranque real con `/actuator/health` en `UP` (los 4 que persisten, contra un Postgres real de prueba).
- Frontend: `npm run lint`, `npm run build` y `npm test` (Vitest + Testing Library) pasan limpios.
- `docker compose ... config` valido para los 3 archivos de compose restantes (`apps`, `mq`, `kafka` — no hay `infra/identity`, ver mas abajo).
- Stack completo de `infra/apps` levantado de punto a punto: 12 contenedores saludables, solo BFF alcanzable desde el host.
- Cluster real de RabbitMQ (perfil deploy, 2 nodos) y de Kafka (perfil deploy, 3 brokers, factor de replicacion 3) verificados con `rabbitmqctl cluster_status` y `kafka-topics --describe` respectivamente.
- Los tres servicios que validan JWT (`bff`, `catalog`, `bookings`) tienen una suite de tests que firma sus propios JWT con una clave RSA local y los sirve contra un servidor JDK `HttpServer` que simula el discovery/JWKS de Entra — nunca llaman al tenant real (`docs/designs/entra-migration.md`).

## Autenticacion en el frontend: Entra ID (Azure AD), unico proveedor

Per `docs/DECISIONES_PROFESOR.md` #6, Entra ID es el unico proveedor de identidad, en todo entorno — no hay proveedor local, no hay rama de codigo por emisor. `src/auth/MsalAuthProvider.ts` es la unica implementacion (`@azure/msal-browser`), instanciada incondicionalmente por `AuthContextProvider`; el unico seam adicional (`SessionProvider` en `src/auth/authRegistry.ts`) existe para permitir que los tests de componentes inyecten un doble de prueba (`src/test/fakeAuthProvider.ts`) en vez de una instancia real de MSAL, no para seleccionar entre proveedores.

**Ningun agente hace commits.** Los commits los hace la persona, nunca un agente, y los mensajes de commit no llevan atribucion de IA.
