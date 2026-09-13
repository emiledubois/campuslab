#!/usr/bin/env bash
# Demo-readiness.md §4/Decision 2 - seeds catalog via direct SQL, then drives bookings
# through the real POST /api/bookings / PUT /api/bookings/{id}/status API (via the real
# BFF) using mock-jwks-minted tokens, so audit's timeline and report's KPI tables are
# populated by the same real producers a browser would use - never a direct INSERT into
# bookings' own tables. Idempotent on both sides: catalog via ON CONFLICT DO NOTHING
# (AC4), bookings via a per-slot `notes` marker checked through GET /api/bookings before
# creating anything (review iteration 3, finding 1) - safe to re-run any number of times
# against a stack that is never torn down, without exhausting any resource's stock.
#
# Run via scripts/seed-demo-data.sh, which starts the app stack (with .env's
# OIDC_ISSUER_URI/OIDC_AUDIENCE already pointed at a mock-jwks it started first) and
# owns mock-jwks' lifecycle for the whole session - this script only requires mock-jwks
# already be running (review iteration 1, findings 1/3), it never starts or stops it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/mock-jwks.sh
source "${REPO_ROOT}/scripts/lib/mock-jwks.sh"

ENV_FILE="${ENV_FILE:-${REPO_ROOT}/.env}"
if [ ! -f "$ENV_FILE" ]; then
    echo "seed: $ENV_FILE not found - copy .env.example to .env and fill it in first" >&2
    exit 1
fi
set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a

# Review iteration 1, finding 2: same explicit project name as scripts/cold-start.sh,
# used below to find the real catalog-db container regardless of which directory the
# first -f file lives in.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-campuslab}"

BFF_URL="${BFF_URL:-http://localhost:8080}"

# Same three files, same merged project, as scripts/cold-start.sh (Decision 1) - used
# only to resolve the real catalog-db container id below, never to start/stop anything.
COMPOSE_FILES=(-f "${REPO_ROOT}/infra/mq/compose.yml" -f "${REPO_ROOT}/infra/kafka/compose.yml" -f "${REPO_ROOT}/infra/apps/compose.yml")

# Fixed, obviously-fake demo identities (§7 A02) - one per role this slice needs to drive
# API calls as, never a real name/email.
STUDENT_OID="c0000000-0000-4000-8000-0000000000e1"
TECNICO_OID="c0000000-0000-4000-8000-0000000000e2"

# Catalog seed's own fixed EQUIPO/INSUMO ids (infra/apps/seed/catalog-seed.sql) - spread
# across five different resources so no single resource's stock is exhausted by seeding.
RESOURCE_SOLICITADA="a0000000-0000-4000-8000-000000000004"
RESOURCE_APROBADA="a0000000-0000-4000-8000-000000000005"
RESOURCE_EN_PREPARACION="a0000000-0000-4000-8000-000000000006"
RESOURCE_EN_USO="a0000000-0000-4000-8000-000000000007"
RESOURCE_DEVUELTA="a0000000-0000-4000-8000-000000000008"
RESOURCE_CANCELADA="a0000000-0000-4000-8000-000000000004"

# Review iteration 3, finding 1: bookings never restore stock on DEVUELTA/CANCELADA
# (approval-saga.md's compensation only fires on a lost optimistic-lock race - correct,
# unchanged domain behaviour, out of scope for this slice). Re-running this script
# against a stack that is never torn down (scripts/seed-demo-data.sh's own documented
# rehearsal workflow) would otherwise create a fresh EN_PREPARACION/EN_USO booking every
# time, permanently consuming one more unit of that resource's limited stock per
# invocation until it runs out. Fixed instead: each lifecycle slot is tagged with its own
# distinctive, fixed `notes` marker, and GET /api/bookings (as TECNICO, which sees every
# booking regardless of owner) is checked for that marker before creating anything, so a
# slot already seeded by a prior invocation is skipped entirely - true no-op on the
# bookings side, matching catalog-seed.sql's own ON CONFLICT DO NOTHING idempotency.
SEED_MARKER="[seed-demo-data]"
NOTES_SOLICITADA="${SEED_MARKER} slot=solicitada"
NOTES_APROBADA="${SEED_MARKER} slot=aprobada"
NOTES_EN_PREPARACION="${SEED_MARKER} slot=en_preparacion"
NOTES_EN_USO="${SEED_MARKER} slot=en_uso"
NOTES_DEVUELTA="${SEED_MARKER} slot=devuelta"
NOTES_CANCELADA="${SEED_MARKER} slot=cancelada"

catalog_db_container_id() {
    local id
    id="$(docker compose --env-file "$ENV_FILE" "${COMPOSE_FILES[@]}" ps -q catalog-db)"
    if [ -z "$id" ]; then
        echo "seed: catalog-db container not found under project '${COMPOSE_PROJECT_NAME}' - is the stack up? Run scripts/cold-start.sh (or scripts/seed-demo-data.sh) first." >&2
        exit 1
    fi
    echo "$id"
}

apply_catalog_seed() {
    echo "seed: applying catalog-seed.sql to catalog-db"
    # Review iteration 1, finding 2: resolved via `docker compose ... ps -q`, never a
    # hardcoded container name - Compose's own project-naming default (derived from the
    # first -f file's directory) previously produced e.g. mq-catalog-db-1, not catalog-db.
    local container_id
    container_id="$(catalog_db_container_id)"
    docker exec -i "$container_id" psql -v ON_ERROR_STOP=1 -U "${CATALOG_DB_USER}" -d "${CATALOG_DB_NAME}" \
        < "${REPO_ROOT}/infra/apps/seed/catalog-seed.sql"
    local count
    count="$(docker exec -i "$container_id" psql -tA -U "${CATALOG_DB_USER}" -d "${CATALOG_DB_NAME}" \
        -c "SELECT count(*) FROM catalog_resource")"
    echo "seed: catalog_resource row count after seed = ${count}"
}

create_booking() {
    local student_token="$1"
    local resource_id="$2"
    local start="$3"
    local end="$4"
    local notes="$5"
    local body status response
    body="$(jq -n --arg resourceId "$resource_id" --arg start "$start" --arg end "$end" --arg notes "$notes" \
        '{resourceId: $resourceId, requestedStart: $start, requestedEnd: $end, notes: $notes}')"
    response="$(curl -sS -w '\n%{http_code}' -X POST "${BFF_URL}/api/bookings" \
        -H "Authorization: Bearer ${student_token}" -H "Content-Type: application/json" -d "$body")"
    status="$(echo "$response" | tail -n1)"
    response="$(echo "$response" | sed '$d')"
    echo "seed: action=[CREATE_BOOKING] resourceId=[${resource_id}] httpStatus=[${status}]" >&2
    if [ "$status" != "201" ]; then
        echo "seed: unexpected status creating booking: ${response}" >&2
        exit 1
    fi
    echo "$response" | jq -r '.id'
}

# Review iteration 3, finding 1 - idempotency helpers. TECNICO is used (not the seed
# student) because BookingService.list only scopes results to the caller's own bookings
# for ESTUDIANTE (BookingService.java's isEstudiante branch) - TECNICO/ADMIN/AUDITOR see
# every booking regardless of owner, which is what a reliable existence check needs.
list_bookings_json() {
    local token="$1"
    local response status body
    response="$(curl -sS -w '\n%{http_code}' "${BFF_URL}/api/bookings" -H "Authorization: Bearer ${token}")"
    status="$(echo "$response" | tail -n1)"
    body="$(echo "$response" | sed '$d')"
    if [ "$status" != "200" ]; then
        echo "seed: unexpected status listing bookings: ${body}" >&2
        exit 1
    fi
    echo "$body"
}

seeded_booking_exists() {
    local bookings_json="$1"
    local notes_marker="$2"
    local match
    match="$(echo "$bookings_json" | jq -r --arg n "$notes_marker" '[.[] | select(.notes == $n)] | length')"
    [ "$match" -gt 0 ]
}

set_status() {
    local token="$1"
    local booking_id="$2"
    local target_status="$3"
    local status response
    response="$(curl -sS -w '\n%{http_code}' -X PUT "${BFF_URL}/api/bookings/${booking_id}/status" \
        -H "Authorization: Bearer ${token}" -H "Content-Type: application/json" \
        -d "{\"status\":\"${target_status}\"}")"
    status="$(echo "$response" | tail -n1)"
    response="$(echo "$response" | sed '$d')"
    echo "seed: action=[SET_STATUS] bookingId=[${booking_id}] targetStatus=[${target_status}] httpStatus=[${status}]" >&2
    if [ "$status" != "200" ]; then
        echo "seed: unexpected status transitioning booking ${booking_id} to ${target_status}: ${response}" >&2
        exit 1
    fi
}

main() {
    # Review iteration 1, finding 1/3: mock-jwks' lifecycle belongs solely to
    # scripts/seed-demo-data.sh, which starts it before this script runs and tears it
    # down only once the whole session (cold-start -> seed -> verify) is done - this
    # script only requires it already be running, never starts or stops it itself. Fail
    # fast, before touching catalog-db, if it isn't.
    require_mock_jwks_running

    apply_catalog_seed

    local student_token tecnico_token
    student_token="$(mint_token ESTUDIANTE "$STUDENT_OID")"
    tecnico_token="$(mint_token TECNICO "$TECNICO_OID")"

    local start end
    start="$(date -u -d '+1 day' +%Y-%m-%dT%H:%M:%SZ)"
    end="$(date -u -d '+1 day +2 hours' +%Y-%m-%dT%H:%M:%SZ)"

    echo "seed: driving bookings through the real API to cover all six lifecycle states"

    # Review iteration 3, finding 1: existence checked once, up front, against the same
    # snapshot for all six slots (an extra booking created concurrently by something else
    # between here and the checks below is not this script's concern - it only reasons
    # about its own fixed marker set).
    local bookings_json
    bookings_json="$(list_bookings_json "$tecnico_token")"

    # SOLICITADA: created, left untouched.
    if seeded_booking_exists "$bookings_json" "$NOTES_SOLICITADA"; then
        echo "seed: action=[SKIP] slot=[SOLICITADA] reason=[already seeded]"
    else
        create_booking "$student_token" "$RESOURCE_SOLICITADA" "$start" "$end" "$NOTES_SOLICITADA" >/dev/null
    fi

    # APROBADA: created, approved by TECNICO, left there.
    if seeded_booking_exists "$bookings_json" "$NOTES_APROBADA"; then
        echo "seed: action=[SKIP] slot=[APROBADA] reason=[already seeded]"
    else
        local booking_aprobada
        booking_aprobada="$(create_booking "$student_token" "$RESOURCE_APROBADA" "$start" "$end" "$NOTES_APROBADA")"
        set_status "$tecnico_token" "$booking_aprobada" APROBADA
    fi

    # EN_PREPARACION: created, approved, moved to prep, left there.
    if seeded_booking_exists "$bookings_json" "$NOTES_EN_PREPARACION"; then
        echo "seed: action=[SKIP] slot=[EN_PREPARACION] reason=[already seeded]"
    else
        local booking_prep
        booking_prep="$(create_booking "$student_token" "$RESOURCE_EN_PREPARACION" "$start" "$end" "$NOTES_EN_PREPARACION")"
        set_status "$tecnico_token" "$booking_prep" APROBADA
        set_status "$tecnico_token" "$booking_prep" EN_PREPARACION
    fi

    # EN_USO: created, approved, prep, in use, left there.
    if seeded_booking_exists "$bookings_json" "$NOTES_EN_USO"; then
        echo "seed: action=[SKIP] slot=[EN_USO] reason=[already seeded]"
    else
        local booking_en_uso
        booking_en_uso="$(create_booking "$student_token" "$RESOURCE_EN_USO" "$start" "$end" "$NOTES_EN_USO")"
        set_status "$tecnico_token" "$booking_en_uso" APROBADA
        set_status "$tecnico_token" "$booking_en_uso" EN_PREPARACION
        set_status "$tecnico_token" "$booking_en_uso" EN_USO
    fi

    # DEVUELTA: the full happy-path lifecycle, end to end.
    if seeded_booking_exists "$bookings_json" "$NOTES_DEVUELTA"; then
        echo "seed: action=[SKIP] slot=[DEVUELTA] reason=[already seeded]"
    else
        local booking_devuelta
        booking_devuelta="$(create_booking "$student_token" "$RESOURCE_DEVUELTA" "$start" "$end" "$NOTES_DEVUELTA")"
        set_status "$tecnico_token" "$booking_devuelta" APROBADA
        set_status "$tecnico_token" "$booking_devuelta" EN_PREPARACION
        set_status "$tecnico_token" "$booking_devuelta" EN_USO
        set_status "$tecnico_token" "$booking_devuelta" DEVUELTA
    fi

    # CANCELADA: created by the student, cancelled by the student while still SOLICITADA.
    if seeded_booking_exists "$bookings_json" "$NOTES_CANCELADA"; then
        echo "seed: action=[SKIP] slot=[CANCELADA] reason=[already seeded]"
    else
        local booking_cancelada
        booking_cancelada="$(create_booking "$student_token" "$RESOURCE_CANCELADA" "$start" "$end" "$NOTES_CANCELADA")"
        set_status "$student_token" "$booking_cancelada" CANCELADA
    fi

    echo "seed: outcome=[DONE] bookings verified/created across SOLICITADA, APROBADA, EN_PREPARACION, EN_USO, DEVUELTA, CANCELADA"
}

main "$@"
