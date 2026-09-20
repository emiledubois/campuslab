#!/usr/bin/env bash
# docs/DEMO_LOCAL.md - prueba end-to-end del stack local sin tenant de Entra.
#
# Complementa a scripts/verify-deploy.sh (salud, topologia y negativos estructurales)
# ejercitando el ciclo de negocio completo con tokens reales RS256 emitidos por el doble
# mock-jwks: crear reserva, aprobarla, comprobar propiedad (IDOR), transicion ilegal,
# timeline de auditoria y KPIs. Solo lectura salvo la reserva que crea a proposito.
#
# Requiere el stack arriba y mock-jwks corriendo - es decir, despues de
# scripts/seed-demo-data.sh. Nunca arranca ni detiene nada.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/mock-jwks.sh
source "${REPO_ROOT}/scripts/lib/mock-jwks.sh"

BFF_URL="${BFF_URL:-http://localhost:8080}"

ADMIN_OID="${SEED_ADMIN_OID:-c0000000-0000-4000-8000-0000000000a1}"
TECNICO_OID="${SEED_TECNICO_OID:-c0000000-0000-4000-8000-0000000000e2}"
STUDENT_OID="${SEED_STUDENT_OID:-c0000000-0000-4000-8000-0000000000e1}"
STUDENT2_OID="${SEED_STUDENT2_OID:-c0000000-0000-4000-8000-0000000000e3}"
AUDITOR_OID="${SEED_AUDITOR_OID:-c0000000-0000-4000-8000-0000000000a2}"

# Recurso EQUIPO de infra/apps/seed/catalog-seed.sql con stock suficiente para una
# reserva adicional por corrida.
RESOURCE_ID="${DEMO_TEST_RESOURCE_ID:-a0000000-0000-4000-8000-000000000006}"

PASS=0
FAIL=0

pass() { PASS=$((PASS + 1)); echo "  PASS  $1"; }
fail() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }

# status <method> <path> [token] [body] - imprime solo el codigo HTTP.
status() {
    local method="$1" path="$2" token="${3:-}" body="${4:-}"
    local args=(-sS -o /dev/null -w '%{http_code}' -X "$method" "${BFF_URL}${path}")
    [ -n "$token" ] && args+=(-H "Authorization: Bearer ${token}")
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
    curl "${args[@]}" 2>/dev/null
}

# body_of <method> <path> <token> [body] - imprime solo el cuerpo de la respuesta.
body_of() {
    local method="$1" path="$2" token="$3" body="${4:-}"
    local args=(-sS -X "$method" "${BFF_URL}${path}" -H "Authorization: Bearer ${token}")
    [ -n "$body" ] && args+=(-H 'Content-Type: application/json' -d "$body")
    curl "${args[@]}" 2>/dev/null
}

expect() {
    local label="$1" expected="$2" actual="$3"
    if [ "$actual" = "$expected" ]; then
        pass "${label} (${actual})"
    else
        fail "${label} - esperado ${expected}, recibido ${actual}"
    fi
}

expect_one_of() {
    local label="$1" actual="$2"
    shift 2
    local candidate
    for candidate in "$@"; do
        if [ "$actual" = "$candidate" ]; then
            pass "${label} (${actual})"
            return
        fi
    done
    fail "${label} - esperado uno de [$*], recibido ${actual}"
}

echo "demo-test: comprobando que mock-jwks este corriendo"
require_mock_jwks_running

ADMIN_TOKEN="$(mint_token ADMIN "$ADMIN_OID")"
TECNICO_TOKEN="$(mint_token TECNICO "$TECNICO_OID")"
STUDENT_TOKEN="$(mint_token ESTUDIANTE "$STUDENT_OID")"
STUDENT2_TOKEN="$(mint_token ESTUDIANTE "$STUDENT2_OID")"
AUDITOR_TOKEN="$(mint_token AUDITOR "$AUDITOR_OID")"

echo
echo "== 1. Salud e identidad =="
expect "BFF responde /actuator/health" "200" "$(status GET /actuator/health)"
expect "GET /api/me con token valido" "200" "$(status GET /api/me "$STUDENT_TOKEN")"

echo
echo "== 2. Autenticacion negativa =="
expect "Sin token -> 401" "401" "$(status GET /api/me)"
expect "Token corrupto -> 401" "401" "$(status GET /api/me "not-a-jwt")"
expect "Firma alterada -> 401" "401" "$(status GET /api/me "${STUDENT_TOKEN}x")"

echo
echo "== 3. Autorizacion por rol =="
expect "ADMIN ve el catalogo" "200" "$(status GET /api/catalog/resources "$ADMIN_TOKEN")"
expect "TECNICO ve el catalogo" "200" "$(status GET /api/catalog/resources "$TECNICO_TOKEN")"
expect "ESTUDIANTE NO ve el catalogo" "403" "$(status GET /api/catalog/resources "$STUDENT_TOKEN")"
expect "AUDITOR NO ve reporteria" "403" "$(status GET /api/report/kpis "$AUDITOR_TOKEN")"
expect "ADMIN ve reporteria" "200" "$(status GET /api/report/kpis "$ADMIN_TOKEN")"
expect "AUDITOR ve auditoria" "200" "$(status GET /api/audit/timeline "$AUDITOR_TOKEN")"
expect "ESTUDIANTE NO ve auditoria" "403" "$(status GET /api/audit/timeline "$STUDENT_TOKEN")"
expect "ESTUDIANTE NO ve admin de colas" "403" "$(status GET /api/admin/mq/queues "$STUDENT_TOKEN")"

echo
echo "== 4. Ciclo de vida de una reserva =="
START="$(date -u -d '+3 days 09:00' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+3d +%Y-%m-%dT09:00:00Z)"
END="$(date -u -d '+3 days 11:00' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -v+3d +%Y-%m-%dT11:00:00Z)"
CREATE_BODY="$(printf '{"resourceId":"%s","requestedStart":"%s","requestedEnd":"%s","notes":"demo-test"}' \
    "$RESOURCE_ID" "$START" "$END")"

CREATED="$(body_of POST /api/bookings "$STUDENT_TOKEN" "$CREATE_BODY")"
BOOKING_ID="$(echo "$CREATED" | jq -r '.id // empty' 2>/dev/null)"

if [ -n "$BOOKING_ID" ]; then
    pass "ESTUDIANTE crea reserva (${BOOKING_ID})"
else
    fail "ESTUDIANTE crea reserva - respuesta: ${CREATED}"
    echo
    echo "demo-test: sin reserva no se puede continuar. PASS=${PASS} FAIL=${FAIL}"
    exit 1
fi

expect "El dueno ve su reserva" "200" "$(status GET "/api/bookings/${BOOKING_ID}" "$STUDENT_TOKEN")"
expect_one_of "IDOR: otro estudiante NO la alcanza" \
    "$(status GET "/api/bookings/${BOOKING_ID}" "$STUDENT2_TOKEN")" "403" "404"
expect_one_of "Transicion ilegal SOLICITADA->EN_USO rechazada" \
    "$(status PUT "/api/bookings/${BOOKING_ID}/status" "$STUDENT_TOKEN" '{"status":"EN_USO"}')" "403" "409"
expect "TECNICO aprueba la reserva" "200" \
    "$(status PUT "/api/bookings/${BOOKING_ID}/status" "$TECNICO_TOKEN" '{"status":"APROBADA"}')"

echo
echo "== 5. Propagacion asincrona (RabbitMQ + Kafka) =="
echo "  esperando a que el evento llegue a auditoria..."
FOUND="no"
for _ in $(seq 1 15); do
    if body_of GET /api/audit/timeline "$AUDITOR_TOKEN" | grep -q "$BOOKING_ID"; then
        FOUND="si"
        break
    fi
    sleep 2
done
if [ "$FOUND" = "si" ]; then
    pass "La reserva aparece en el timeline de auditoria"
else
    fail "La reserva no aparecio en el timeline tras 30s"
fi

expect "ADMIN consulta colas de RabbitMQ" "200" "$(status GET /api/admin/mq/queues "$ADMIN_TOKEN")"
expect "ADMIN consulta topicos de Kafka" "200" "$(status GET /api/admin/kafka/topics "$ADMIN_TOKEN")"
expect "ADMIN consulta consumer groups" "200" "$(status GET /api/admin/kafka/consumer-groups "$ADMIN_TOKEN")"
expect "ADMIN consulta DLT" "200" "$(status GET /api/admin/kafka/dlt "$ADMIN_TOKEN")"

echo
echo "=========================================="
echo " demo-test: PASS=${PASS}  FAIL=${FAIL}"
echo "=========================================="
[ "$FAIL" -eq 0 ] || exit 1
