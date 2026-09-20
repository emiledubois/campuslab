# CampusLab

Sistema de reserva de laboratorios y equipos academicos para una red de 20 laboratorios. Proyecto del Caso 2 de **DSY1107 — Desarrollo Cloud Native I**.

Integrantes: [Nombre 1], [Nombre 2], [Nombre 3]

---

## Arquitectura

```
Navegador (React + MSAL)
        |
   API Gateway  (JWT Authorizer)
        |
   ms-campuslab-bff  (Spring Security: valida el token nuevamente)
        |
   +----+-------------------+---------------------+
   |                        |                     |
 bookings               catalog              audit / report
   |                        |                     ^
   +--> RabbitMQ --> notify |                     |
   +--> Kafka --------------+---------------------+
```

| Capa | Tecnologia |
|---|---|
| Frontend | React 19, Vite, TypeScript, Tailwind, `@azure/msal-browser` y `@azure/msal-react` |
| Backend | Java 21, Spring Boot 3.5, Maven wrapper por servicio |
| Identidad | Microsoft Entra ID (OIDC), roles como app roles |
| Persistencia | PostgreSQL, una base por servicio, migraciones Flyway |
| Mensajeria | RabbitMQ (colas de comandos con DLQ) |
| Streaming | Kafka con Zookeeper (eventos de reserva y auditoria) |
| Ejecucion | Docker Compose |

### Servicios

| Directorio | Rol | Base de datos |
|---|---|---|
| `ms-campuslab-bff` | Unico punto de entrada; valida el token y orquesta | — |
| `ms-campuslab-bookings` | Reservas y maquina de estados | si |
| `ms-campuslab-catalog` | Laboratorios, equipos e insumos; cupo y stock | si |
| `ms-campuslab-notify` | Consumidor de RabbitMQ (correo y tickets) | — |
| `ms-campuslab-audit` | Consumidor de Kafka; timeline de eventos | si |
| `ms-campuslab-report` | Consumidor de Kafka; KPIs de ocupacion | si |
| `ms-campuslab-mq-admin` | Declara y supervisa la topologia de RabbitMQ | — |
| `ms-campuslab-kafka-admin` | Crea topicos y reporta consumer lag | — |
| `frontend-campuslab` | Aplicacion React | — |
| `infra/` | Compose de aplicaciones, mensajeria y streaming | — |

---

## Requisitos

- Docker y el plugin `docker compose`
- Node.js 20 o superior (para el frontend)
- Conexion a internet: la autenticacion se valida contra Microsoft Entra ID

No hace falta Java ni Maven instalados: los servicios se compilan dentro de sus imagenes.

---

## Puesta en marcha

### 1. Variables de entorno

```bash
cp .env.example .env
cp frontend-campuslab/.env.example frontend-campuslab/.env
```

Completar en ambos archivos los valores de identidad. Se entregan junto con este repositorio, en el correo de entrega:

| Archivo | Variable |
|---|---|
| `.env` | `OIDC_ISSUER_URI`, `OIDC_AUDIENCE` |
| `frontend-campuslab/.env` | `VITE_OIDC_AUTHORITY`, `VITE_OIDC_CLIENT_ID`, `VITE_API_SCOPE`, `VITE_API_BASE_URL` |

Las credenciales de las cuentas de prueba se entregan por el mismo medio y no estan en el repositorio.

### 2. Levantar el backend

```bash
./scripts/cold-start.sh
```

Compila las ocho imagenes y levanta 16 contenedores: los servicios, sus bases PostgreSQL, RabbitMQ, Zookeeper y Kafka. El script espera a que cada servicio reporte estado saludable antes de continuar. El arranque en frio toma alrededor de un minuto.

### 3. Levantar el frontend

```bash
cd frontend-campuslab
npm ci
npm run dev
```

La aplicacion queda en `http://localhost:5173`.

### 4. Detener

```bash
docker compose -p campuslab down      # conserva los datos
docker compose -p campuslab down -v   # elimina tambien las bases
```

---

## Como revisar el proyecto

### Cuentas de prueba

| Usuario | Rol | Que deberia ver |
|---|---|---|
| `admin@` | ADMIN | Las cinco secciones, incluidos catalogo y reporteria |
| `tecnico@` | TECNICO | Reservas y catalogo; puede aprobar y registrar devoluciones |
| `estudiante@` | ESTUDIANTE | Solo sus propias reservas |
| `estudiante2@` | ESTUDIANTE | Sin reservas propias: sirve para comprobar el aislamiento entre usuarios |
| `auditor@` | AUDITOR | Solo el timeline de auditoria |

### Recorrido sugerido

1. **Autenticacion.** Iniciar sesion con cualquier cuenta. El dashboard muestra el usuario, su rol y el emisor del token, que debe terminar en `/v2.0`.
2. **Autorizacion por rol.** Como `estudiante@`, escribir a mano la ruta `/reports`. El menu no ofrece el enlace, y el servidor responde con error aunque se acceda por URL: la restriccion es del backend, no de la interfaz.
3. **Aislamiento entre usuarios.** Comparar las reservas de `estudiante@` y `estudiante2@`. Un estudiante no alcanza las reservas de otro aunque tenga el mismo rol.
4. **Ciclo de vida.** Como `estudiante@`, solicitar una reserva. Como `tecnico@`, aprobarla y avanzarla. Las transiciones invalidas se rechazan en el servidor.
5. **Propagacion asincrona.** Tras aprobar, revisar el timeline con `auditor@` y los KPIs con `admin@`. El mismo evento alimenta ambos consumidores.

### Donde mirar el codigo

| Que | Donde |
|---|---|
| Validacion del JWT | `ms-campuslab-bff/src/main/java/cl/campuslab/bff/security/` |
| Reglas de rol por endpoint | `SecurityConfig.java` de cada servicio |
| Maquina de estados y propiedad de la reserva | `ms-campuslab-bookings/.../BookingService.java` |
| Saga de aprobacion y descuento de stock | `ms-campuslab-bookings` y `ms-campuslab-catalog` |
| Topologia de colas, exchanges y DLQ | `ms-campuslab-mq-admin` |
| Topicos, particiones y retencion | `ms-campuslab-kafka-admin` |
| Integracion MSAL | `frontend-campuslab/src/auth/` |

---

## Seguridad

- El token se valida **dos veces**: en el API Gateway y otra vez en el BFF. El BFF es alcanzable por red, asi que no asume que toda peticion paso por el gateway.
- El frontend es un cliente publico con PKCE: **no existe ningun client secret** en el repositorio.
- El rol autoriza el endpoint; la **propiedad** autoriza el dato. Un estudiante no puede leer ni modificar la reserva de otro, y la respuesta se enmascara como 404.
- Ningun servicio de dominio se expone publicamente: solo el BFF recibe trafico externo.
- Las credenciales viven en `.env`, que esta excluido del repositorio. `.env.example` documenta cada variable sin valores reales.

---

## Pruebas

```bash
# Backend, por servicio
cd ms-campuslab-bookings && ./mvnw test

# Frontend
cd frontend-campuslab && npm run lint && npm test && npm run build
```

---

## Notas de alcance

- **React en lugar de Angular** y **PostgreSQL en lugar de Oracle**: ambas desviaciones respecto del enunciado fueron autorizadas por el docente.
- El perfil local levanta **un nodo de RabbitMQ y un broker de Kafka**. El perfil de despliegue define el cluster de dos nodos y los tres brokers con factor de replica 3 que indica el enunciado. Los nombres de colas, exchanges, topicos, particiones y politicas de retencion son identicos en ambos perfiles; el factor de replica es el unico valor que cambia.
- El despliegue en AWS sobre tres instancias EC2 quedo definido en los perfiles de Compose pero no ejecutado. La demostracion se realiza sobre el ambiente local.
