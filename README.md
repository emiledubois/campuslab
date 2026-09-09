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
| Identidad | OIDC — Keycloak local, Azure AD (Entra ID) en despliegue, intercambiables solo por variables de entorno |
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
  identity/compose.yml      Keycloak (solo local)
  apps/compose.yml          los 8 servicios + sus Postgres
  mq/compose.yml            RabbitMQ + Management UI
  kafka/compose.yml         Zookeeper + Kafka + Kafka UI
```

El camino de llamada es siempre: navegador → API Gateway → `ms-campuslab-bff` → microservicio de dominio. El frontend nunca llama directo a un microservicio de dominio; ningun microservicio de dominio se expone publicamente en compose — solo la BFF publica un puerto al host.

## Requisitos previos

- **Java 21** y **Docker + Docker Compose** (probado con Docker 29.7 / Compose v5).
- **Node.js** para el frontend. *Nota:* en esta maquina el `node` del sistema esta roto (falta `libada.so.3`, aparentemente por una actualizacion parcial de paquetes de Arch). Se instalo Node LTS de forma aislada via `nvm` en `~/.nvm` (sin tocar paquetes del sistema); para usarlo:
  ```bash
  source ~/.nvm/nvm.sh
  nvm use --lts
  ```
  Si tu maquina tiene un `node` funcional del sistema, ignora este paso.

## Arrancar todo localmente

Cada paso fue efectivamente levantado y verificado durante el scaffolding (no son instrucciones sin probar).

1. **Copiar variables de entorno**: `cp .env.example .env` y completar los valores (usuarios/contraseñas de desarrollo, nunca reales/productivos). Docker Compose los toma automaticamente si `.env` esta en la raiz del repo o se referencia con `--env-file`.

2. **Identidad (Keycloak)** — realm `campuslab` con los cuatro roles y un usuario de prueba por rol, importado automaticamente al arrancar:
   ```bash
   docker compose --env-file .env -f infra/identity/compose.yml up -d
   ```
   Keycloak queda en `http://localhost:8081` (se publico en 8081 y no 8080 porque el 8080 lo usa la BFF). Usuarios sembrados (contraseña de desarrollo `Campuslab#2026` para los cuatro): `admin.test`, `tecnico.test`, `estudiante.test`, `auditor.test`. Para obtener un token real de prueba (QA sin clicks manuales):
   ```bash
   curl -s -X POST "http://localhost:8081/realms/campuslab/protocol/openid-connect/token" \
     -d grant_type=password -d client_id=campuslab-spa \
     -d username=estudiante.test -d password='Campuslab#2026' | jq -r .access_token
   ```
   El JWT resultante trae `realm_access.roles` con el rol correspondiente y `aud` incluyendo `campuslab-api` — verificado durante el scaffolding.

3. **RabbitMQ** (perfil `local`, un solo nodo + Management UI):
   ```bash
   docker compose --env-file .env -f infra/mq/compose.yml --profile local up -d
   ```
   Management UI en `http://localhost:15672`. El perfil `deploy` levanta un cluster real de 2 nodos (`rabbitmq-1`/`rabbitmq-2`) sin publicar la UI de administracion.

4. **Kafka** (perfil `local`, Zookeeper + 1 broker + Kafka UI):
   ```bash
   docker compose --env-file .env -f infra/kafka/compose.yml --profile local up -d
   ```
   Kafka UI en `http://localhost:8082`. El perfil `deploy` levanta 3 Zookeeper + 3 brokers (replicacion real verificada) sin publicar la UI.

   Ni RabbitMQ ni Kafka declaran colas/topicos todavia — eso lo hacen `ms-campuslab-mq-admin` y `ms-campuslab-kafka-admin` respectivamente, en una etapa posterior.

5. **Los ocho microservicios + sus bases de datos**:
   ```bash
   docker compose --env-file .env -f infra/apps/compose.yml up -d --build
   ```
   Solo `ms-campuslab-bff` publica un puerto al host (`8080`) — es el unico punto de entrada publico. Ningun otro servicio (bookings, catalog, notify, report, audit, mq-admin, kafka-admin) es alcanzable desde el host; solo entre contenedores de la misma red de compose. Verificar: `curl http://localhost:8080/actuator/health` debe devolver `{"status":"UP"}`.

6. **Frontend**:
   ```bash
   cd frontend-campuslab
   npm install
   npm run dev
   ```
   Sirve en `http://localhost:5173` (coincide con el `redirectUri`/`webOrigins` ya configurado en el cliente `campuslab-spa` de Keycloak).

## Correr un microservicio individual fuera de Docker

```bash
cd ms-campuslab-catalog
SERVER_PORT=8080 DB_URL=jdbc:postgresql://localhost:5432/catalog DB_USERNAME=catalog DB_PASSWORD=catalog ./mvnw spring-boot:run
```
(Los servicios sin persistencia — bff, notify, mq-admin, kafka-admin — solo necesitan `SERVER_PORT`, ninguna variable de base de datos.)

## Verificacion de cada pieza (hecha durante el scaffolding, no solo documentada)

- Los 8 servicios: `./mvnw -q compile` exitoso y arranque real con `/actuator/health` en `UP` (los 4 que persisten, contra un Postgres real de prueba).
- Frontend: `npm run lint`, `npm run build` y `npm test` (Vitest + Testing Library) pasan limpios.
- `docker compose ... config` valido para los 4 archivos de compose.
- Realm de Keycloak importado y tokens password-grant verificados para los 4 roles.
- Stack completo de `infra/apps` levantado de punto a punto: 12 contenedores saludables, solo BFF alcanzable desde el host.
- Cluster real de RabbitMQ (perfil deploy, 2 nodos) y de Kafka (perfil deploy, 3 brokers, factor de replicacion 3) verificados con `rabbitmqctl cluster_status` y `kafka-topics --describe` respectivamente.

## Autenticacion en el frontend: Keycloak local vs Azure AD en despliegue

`@azure/msal-browser` rechaza de forma incondicional cualquier `authority` que no use `https:` (verificado contra el codigo fuente de la libreria, no solo documentacion) — Keycloak local sirve el realm en `http://localhost:8081` sin TLS, por lo que MSAL no puede apuntar directo a el en modo OIDC generico. `src/auth/` se disena como una interfaz `AuthProvider` con dos implementaciones: MSAL para Azure AD en despliegue, y `oidc-client-ts` para Keycloak en desarrollo local, compartiendo un unico cliente HTTP que adjunta el `Bearer <access_token>`. Todavia no hay codigo de autenticacion escrito en el frontend.

**Ningun agente hace commits.** Los commits los hace la persona, nunca un agente, y los mensajes de commit no llevan atribucion de IA.
