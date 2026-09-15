#!/usr/bin/env bash
# Demo-readiness.md Decision 2 / review iteration 1 (findings 1 and 3) - the ONLY
# supported way to bring up the app stack with seeded demo data. This is a distinct,
# local-only, non-Entra workflow, never the real demo path (scripts/cold-start.sh alone,
# pointed at .env's real OIDC_ISSUER_URI/OIDC_AUDIENCE, remains exactly that - AC8's grep
# check for "mock-jwks" still returns nothing against scripts/cold-start.sh/README.md's
# actual demo-start instructions).
#
# What finding 1 broke: running scripts/cold-start.sh (with .env already pointed at
# mock-jwks, per this workflow) BEFORE mock-jwks itself was running meant every
# OIDC-configured service's NimbusJwtDecoder hit an eager, startup-time discovery fetch
# against a host that didn't exist yet and crashed. This script fixes that by owning the
# whole session's ordering explicitly: start mock-jwks first, confirm it healthy, only
# then start the app stack, then seed, then verify - all against that one already-running
# mock-jwks instance.
#
# What finding 3 broke: scripts/seed.sh and scripts/verify-seed.sh each independently
# starting/stopping mock-jwks mid-session could regenerate its RSA key under the same
# `kid`, so a token minted before a restart could fail signature validation against a
# JWKS response cached from after it. This script is now the single owner of mock-jwks'
# lifecycle for the whole session (started here, torn down here on exit) -
# scripts/seed.sh and scripts/verify-seed.sh no longer start or stop it themselves
# (scripts/lib/mock-jwks.sh's require_mock_jwks_running), so there is no remaining path
# for an independent mid-session restart to happen.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/mock-jwks.sh
source "${REPO_ROOT}/scripts/lib/mock-jwks.sh"

ENV_FILE="${ENV_FILE:-${REPO_ROOT}/.env}"
export ENV_FILE
if [ ! -f "$ENV_FILE" ]; then
    echo "seed-demo-data: $ENV_FILE not found - copy .env.example to .env and fill it in first" >&2
    exit 1
fi

# QA iteration 1, finding 1: this script does not read SEED_*_OID itself, but it is the
# parent process for scripts/seed.sh (line 96) and scripts/verify-seed.sh (line 99) below -
# `set -a; source "$ENV_FILE"` would otherwise re-export these four vars as "" into THIS
# process's own environment, which both children then inherit verbatim, discarding whatever
# the operator exported in their own shell before invoking this script. Capture/restore
# around the source, identical pattern to scripts/seed.sh's own fix, so the value handed
# down to both children is the operator's real override, not .env's blank default line.
_PRESET_SEED_ADMIN_OID="${SEED_ADMIN_OID:-}"
_PRESET_SEED_TECNICO_OID="${SEED_TECNICO_OID:-}"
_PRESET_SEED_STUDENT_OID="${SEED_STUDENT_OID:-}"
_PRESET_SEED_AUDITOR_OID="${SEED_AUDITOR_OID:-}"

# Review iteration 1, finding 2: the same explicit project name scripts/cold-start.sh,
# scripts/seed.sh and scripts/measure-cold-boot.sh all pin, inherited by every
# subprocess this script invokes below.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-campuslab}"

set -a
# shellcheck source=/dev/null
source "$ENV_FILE"
set +a
[ -n "$_PRESET_SEED_ADMIN_OID" ] && export SEED_ADMIN_OID="$_PRESET_SEED_ADMIN_OID"
[ -n "$_PRESET_SEED_TECNICO_OID" ] && export SEED_TECNICO_OID="$_PRESET_SEED_TECNICO_OID"
[ -n "$_PRESET_SEED_STUDENT_OID" ] && export SEED_STUDENT_OID="$_PRESET_SEED_STUDENT_OID"
[ -n "$_PRESET_SEED_AUDITOR_OID" ] && export SEED_AUDITOR_OID="$_PRESET_SEED_AUDITOR_OID"

BFF_URL="${BFF_URL:-http://localhost:8080}"

# The BFF has no dependent service in infra/apps/compose.yml's depends_on graph (its own
# health has never gated anyone else's startup - Decision 1), so `docker compose up -d`
# returns as soon as it's created/started, not once it's actually serving - seed.sh's
# first real API call can otherwise race a BFF that hasn't finished starting yet.
wait_for_bff_ready() {
    echo "seed-demo-data: waiting for the BFF to actually be serving requests"
    local attempt
    for attempt in $(seq 1 60); do
        if curl -sf "${BFF_URL}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
            echo "seed-demo-data: outcome=[BFF_READY]"
            return 0
        fi
        sleep 2
    done
    echo "seed-demo-data: outcome=[BFF_NOT_READY] gave up after 120s" >&2
    return 1
}

check_env_points_at_mock_jwks() {
    if [ "${OIDC_ISSUER_URI:-}" = "$MOCK_JWKS_CONTAINER_ISSUER" ] && [ "${OIDC_AUDIENCE:-}" = "$MOCK_JWKS_CONTAINER_AUDIENCE" ]; then
        return 0
    fi
    echo "seed-demo-data: ${ENV_FILE}'s OIDC_ISSUER_URI/OIDC_AUDIENCE are not pointed at mock-jwks." >&2
    echo "This workflow requires them set to (for this local seed/verify session only -" >&2
    echo "never for the real demo, which always points at the real Entra tenant instead):" >&2
    echo "  OIDC_ISSUER_URI=${MOCK_JWKS_CONTAINER_ISSUER}" >&2
    echo "  OIDC_AUDIENCE=${MOCK_JWKS_CONTAINER_AUDIENCE}" >&2
    echo "Found instead:" >&2
    echo "  OIDC_ISSUER_URI=${OIDC_ISSUER_URI:-<empty>}" >&2
    echo "  OIDC_AUDIENCE=${OIDC_AUDIENCE:-<empty>}" >&2
    return 1
}

main() {
    check_env_points_at_mock_jwks

    echo "seed-demo-data: starting mock-jwks (long-lived for this whole session - finding 1/3)"
    start_mock_jwks
    trap stop_mock_jwks EXIT

    echo "seed-demo-data: starting the app stack (scripts/cold-start.sh)"
    "${REPO_ROOT}/scripts/cold-start.sh"

    wait_for_bff_ready

    echo "seed-demo-data: seeding catalog + driving bookings through the real API (scripts/seed.sh)"
    "${REPO_ROOT}/scripts/seed.sh"

    echo "seed-demo-data: verifying the seed through the real BFF-fronted API (scripts/verify-seed.sh)"
    "${REPO_ROOT}/scripts/verify-seed.sh"

    echo "seed-demo-data: outcome=[DONE] stack is up, seeded, and verified"
}

main "$@"
