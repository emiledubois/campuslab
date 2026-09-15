#!/usr/bin/env bash
# Demo-readiness.md §4/Decision 2/AC5-AC7 - mints its own ADMIN/TECNICO/ESTUDIANTE/AUDITOR
# tokens from mock-jwks and re-reads the seeded state back through the real, running
# BFF-fronted APIs, proving scripts/seed.sh's data actually flowed through the real
# producers (bookings' write path, audit/report's Kafka consumers) rather than a bypass.
# Never calls the real Entra tenant (AC7) - every token here comes from mock-jwks, every
# outbound call target is either mock-jwks or the BFF, both fixed local addresses.
#
# Run via scripts/seed-demo-data.sh, which owns mock-jwks' lifecycle for the whole
# session - this script only requires mock-jwks already be running (review iteration 1,
# findings 1/3), it never starts or stops it.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/mock-jwks.sh
source "${REPO_ROOT}/scripts/lib/mock-jwks.sh"

BFF_URL="${BFF_URL:-http://localhost:8080}"

ADMIN_OID="${SEED_ADMIN_OID:-c0000000-0000-4000-8000-0000000000a1}"
TECNICO_OID="${SEED_TECNICO_OID:-c0000000-0000-4000-8000-0000000000e2}"
STUDENT_OID="${SEED_STUDENT_OID:-c0000000-0000-4000-8000-0000000000e1}"
AUDITOR_OID="${SEED_AUDITOR_OID:-c0000000-0000-4000-8000-0000000000a2}"

FAILURES=0

fail() {
    echo "verify-seed: FAIL - $1" >&2
    FAILURES=$((FAILURES + 1))
}

get_json() {
    local token="$1"
    local path="$2"
    local response status body
    response="$(curl -sS -w '\n%{http_code}' "${BFF_URL}${path}" -H "Authorization: Bearer ${token}")"
    status="$(echo "$response" | tail -n1)"
    body="$(echo "$response" | sed '$d')"
    echo "verify-seed: action=[GET] path=[${path}] httpStatus=[${status}]" >&2
    if [ "$status" != "200" ]; then
        fail "GET ${path} returned ${status}, expected 200"
        echo "{}"
        return
    fi
    echo "$body"
}

main() {
    # Review iteration 1, finding 1/3: mock-jwks' lifecycle belongs solely to
    # scripts/seed-demo-data.sh - see scripts/seed.sh's own identical comment.
    require_mock_jwks_running

    local admin_token tecnico_token student_token auditor_token
    admin_token="$(mint_token ADMIN "$ADMIN_OID")"
    tecnico_token="$(mint_token TECNICO "$TECNICO_OID")"
    student_token="$(mint_token ESTUDIANTE "$STUDENT_OID")"
    auditor_token="$(mint_token AUDITOR "$AUDITOR_OID")"

    echo "verify-seed: --- catalog ---"
    local catalog_json catalog_count
    catalog_json="$(get_json "$tecnico_token" "/api/catalog/resources")"
    catalog_count="$(echo "$catalog_json" | jq 'length')"
    echo "verify-seed: catalog resource count = ${catalog_count}"
    [ "$catalog_count" -ge 8 ] || fail "expected at least 8 seeded catalog resources, got ${catalog_count}"

    echo "verify-seed: --- bookings (AC5: lifecycle spread) ---"
    local bookings_json
    bookings_json="$(get_json "$admin_token" "/api/bookings")"
    for target_status in SOLICITADA APROBADA EN_PREPARACION EN_USO DEVUELTA CANCELADA; do
        local count
        count="$(echo "$bookings_json" | jq --arg s "$target_status" '[.[] | select(.status == $s)] | length')"
        echo "verify-seed: bookings in status ${target_status} = ${count}"
        [ "$count" -ge 1 ] || fail "expected at least one booking in status ${target_status}, got ${count}"
    done

    echo "verify-seed: --- bookings (own-visibility check for the seed student) ---"
    local own_bookings_json own_count
    own_bookings_json="$(get_json "$student_token" "/api/bookings")"
    own_count="$(echo "$own_bookings_json" | jq 'length')"
    echo "verify-seed: student's own visible booking count = ${own_count}"
    [ "$own_count" -ge 1 ] || fail "expected the seed student to see at least one own booking"

    echo "verify-seed: --- audit timeline (AC6) ---"
    local timeline_json solicitada_count aprobada_count
    timeline_json="$(get_json "$auditor_token" "/api/audit/timeline")"
    solicitada_count="$(echo "$timeline_json" | jq '[.[] | select(.eventType == "BOOKING_SOLICITADA")] | length')"
    aprobada_count="$(echo "$timeline_json" | jq '[.[] | select(.eventType == "BOOKING_APROBADA")] | length')"
    echo "verify-seed: audit BOOKING_SOLICITADA rows = ${solicitada_count}, BOOKING_APROBADA rows = ${aprobada_count}"
    [ "$solicitada_count" -ge 1 ] || fail "expected at least one BOOKING_SOLICITADA audit row"
    [ "$aprobada_count" -ge 1 ] || fail "expected at least one BOOKING_APROBADA audit row"

    echo "verify-seed: --- report KPIs (AC6) ---"
    local kpis_json completed_count nonzero_buckets
    kpis_json="$(get_json "$admin_token" "/api/report/kpis?range=last24h")"
    completed_count="$(echo "$kpis_json" | jq '.tiempoDeCiclo.completedBookingsCount')"
    nonzero_buckets="$(echo "$kpis_json" | jq '[.reservasPorHora.buckets[]? | select((.count // 0) > 0)] | length')"
    echo "verify-seed: tiempoDeCiclo.completedBookingsCount = ${completed_count}, non-zero reservasPorHora buckets = ${nonzero_buckets}"
    [ "$completed_count" -ge 1 ] || fail "expected tiempoDeCiclo.completedBookingsCount >= 1"
    [ "$nonzero_buckets" -ge 1 ] || fail "expected at least one non-zero reservasPorHora bucket"

    if [ "$FAILURES" -gt 0 ]; then
        echo "verify-seed: outcome=[FAILED] failureCount=[${FAILURES}]" >&2
        exit 1
    fi
    echo "verify-seed: outcome=[PASSED] every seeded surface verified through the real BFF-fronted API"
}

main "$@"
