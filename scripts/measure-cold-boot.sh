#!/usr/bin/env bash
# Demo-readiness.md Decision 3 - measures a real, reproducible cold-boot time.
#
# Start event: the instant this script invokes scripts/cold-start.sh, immediately
# preceded by a genuine `down -v` (containers AND named volumes removed) across all
# three compose files, so Postgres re-runs every Flyway migration from empty and
# Kafka/RabbitMQ re-create their topology from nothing.
#
# Stop event: the first instant both are true, polled every 2s:
#   1. every container the merged `up` started reports health "healthy" (or "running"
#      for the one container with no healthcheck today, kafka-ui);
#   2. a scripted, authenticated round-trip succeeds end-to-end: GET /api/me on the
#      BFF's published port, bearing a mock-jwks-minted ESTUDIANTE token, returns 200
#      with that token's oid/roles echoed back correctly.
#
# mock-jwks is started before the timed window (its own startup time is not part of the
# app stack's cold-boot cost) purely to mint the one token needed for condition 2 above -
# this script is tooling, not the demo path itself (unlike scripts/cold-start.sh, which
# never references it - AC8).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/mock-jwks.sh
source "${REPO_ROOT}/scripts/lib/mock-jwks.sh"

ENV_FILE="${ENV_FILE:-${REPO_ROOT}/.env}"
export ENV_FILE
# Review iteration 1, finding 2: same explicit project name as scripts/cold-start.sh -
# this script's own `down -v`/`ps` calls, and the scripts/cold-start.sh subprocess it
# invokes below (which inherits this exported var), must all agree on one project.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-campuslab}"
BFF_URL="${BFF_URL:-http://localhost:8080}"
POLL_INTERVAL_SECONDS=2

COMPOSE_FILES=(-f "${REPO_ROOT}/infra/mq/compose.yml" -f "${REPO_ROOT}/infra/kafka/compose.yml" -f "${REPO_ROOT}/infra/apps/compose.yml")

# Every container name the merged `up --profile local` starts (design doc Decision 3) -
# the 4 with no healthcheck configured are still required to at least be "running".
NO_HEALTHCHECK_SERVICES=(kafka-ui)

all_containers_healthy() {
    local ids
    ids="$(docker compose --env-file "$ENV_FILE" "${COMPOSE_FILES[@]}" --profile local ps -q 2>/dev/null)"
    if [ -z "$ids" ]; then
        return 1
    fi
    local id name status healthy total
    total=0
    healthy=0
    for id in $ids; do
        total=$((total + 1))
        name="$(docker inspect --format '{{.Name}}' "$id" | sed 's#^/##')"
        status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")"
        if [ "$status" = "healthy" ] || [ "$status" = "running" ]; then
            healthy=$((healthy + 1))
        else
            echo "measure-cold-boot: waiting - container=[${name}] status=[${status}]"
        fi
    done
    echo "measure-cold-boot: ${healthy}/${total} containers healthy/running"
    [ "$healthy" -eq "$total" ] && [ "$total" -ge 16 ]
}

authenticated_round_trip_succeeds() {
    local oid token response status body actual_oid actual_roles
    oid="c0000000-0000-4000-8000-0000000000f1"
    token="$(mint_token ESTUDIANTE "$oid" 2>/dev/null)" || return 1
    response="$(curl -sS -w '\n%{http_code}' "${BFF_URL}/api/me" -H "Authorization: Bearer ${token}" 2>/dev/null)" || return 1
    status="$(echo "$response" | tail -n1)"
    body="$(echo "$response" | sed '$d')"
    if [ "$status" != "200" ]; then
        echo "measure-cold-boot: /api/me returned ${status}, not yet 200"
        return 1
    fi
    actual_oid="$(echo "$body" | jq -r '.oid')"
    actual_roles="$(echo "$body" | jq -r '.roles[0]')"
    if [ "$actual_oid" != "$oid" ] || [ "$actual_roles" != "ESTUDIANTE" ]; then
        echo "measure-cold-boot: /api/me returned 200 but oid/roles did not match the minted token"
        return 1
    fi
    return 0
}

main() {
    if [ ! -f "$ENV_FILE" ]; then
        echo "measure-cold-boot: $ENV_FILE not found - copy .env.example to .env and fill it in first" >&2
        exit 1
    fi

    start_mock_jwks
    trap stop_mock_jwks EXIT

    echo "measure-cold-boot: tearing down (down -v) for a genuinely cold start"
    docker compose --env-file "$ENV_FILE" "${COMPOSE_FILES[@]}" --profile local down -v

    echo "measure-cold-boot: START"
    START_TS="$(date +%s.%N)"

    "${REPO_ROOT}/scripts/cold-start.sh"

    while true; do
        if all_containers_healthy && authenticated_round_trip_succeeds; then
            break
        fi
        sleep "$POLL_INTERVAL_SECONDS"
    done

    STOP_TS="$(date +%s.%N)"
    echo "measure-cold-boot: STOP"

    ELAPSED_ROUNDED="$(awk -v a="$START_TS" -v b="$STOP_TS" 'BEGIN { printf "%.1f", b - a }')"
    echo "measure-cold-boot: outcome=[DONE] elapsedSeconds=[${ELAPSED_ROUNDED}]"
}

main "$@"
