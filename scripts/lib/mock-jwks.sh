# Shared by scripts/seed-demo-data.sh, scripts/seed.sh, scripts/verify-seed.sh and
# scripts/measure-cold-boot.sh (demo-readiness.md Decision 2/3) - never sourced by
# scripts/cold-start.sh itself, which never touches infra/testing/compose.yml at all
# (AC8: the actual demo-start path never references mock-jwks). `source`d, not executed.
#
# Review iteration 1, finding 1/3: mock-jwks must be a single, long-lived process for
# the whole duration of a "local demo with seeded data" session - started once, BEFORE
# scripts/cold-start.sh (so eagerly-fetched OIDC discovery at Spring context startup
# succeeds instead of crashing every OIDC-configured service), and never independently
# restarted mid-session (a restart would mint a fresh RSA key under the same `kid`,
# making already-cached JWKS reject legitimately-minted tokens with "Invalid signature").
# scripts/seed-demo-data.sh is the only script that calls start_mock_jwks/stop_mock_jwks
# for the seed+verify session - it owns that session's lifecycle end to end.
# scripts/seed.sh and scripts/verify-seed.sh only require it already be running
# (require_mock_jwks_running) and never start or stop it themselves, so there is no
# independent-restart path left mid-session for finding 3 to reoccur on.
# scripts/measure-cold-boot.sh also calls start_mock_jwks/stop_mock_jwks, but as a
# wholly separate, single-script, self-contained use (start once at the top of that one
# script, mint exactly one token, stop on that script's own exit) - it is never chained
# with scripts/seed.sh/verify-seed.sh in the same session, so it never re-introduces
# finding 3's independent-mid-session-restart problem either.

MOCK_JWKS_PORT="${MOCK_JWKS_PORT:-1080}"
# Host-side address (scripts run on the host, not inside campuslab-net) used to mint
# tokens via curl.
MOCK_JWKS_URL="http://localhost:${MOCK_JWKS_PORT}/mock-tenant/v2.0"
# Container-side address (matches infra/testing/compose.yml's MOCK_JWKS_ISSUER/
# MOCK_JWKS_AUDIENCE defaults and infra/testing/mock-jwks/app.py's own defaults) - what
# app containers on campuslab-net must be told OIDC_ISSUER_URI/OIDC_AUDIENCE are, for
# this local-only seed/verify session only. Never used for the real demo path.
MOCK_JWKS_CONTAINER_ISSUER="http://mock-jwks:1080/mock-tenant/v2.0"
MOCK_JWKS_CONTAINER_AUDIENCE="api://mock-audience"

# Pinned explicitly so mock-jwks always stays its own, separate Compose project -
# never folded into the app stack's "campuslab" project via an inherited
# COMPOSE_PROJECT_NAME (empirically caught: scripts/measure-cold-boot.sh and
# scripts/seed-demo-data.sh both export COMPOSE_PROJECT_NAME=campuslab for the app
# stack's own container-name resolution - findings 2's fix - and since `docker compose`
# picks project name from the shell environment regardless of which -f files are passed,
# mock-jwks would otherwise silently become a "campuslab" container too, undermining the
# "own compose file, own project, never merged into the demo path" invariant).
MOCK_JWKS_COMPOSE_PROJECT_NAME="campuslab-mock-jwks-testing"

start_mock_jwks() {
    echo "mock-jwks: starting infra/testing/compose.yml (host port ${MOCK_JWKS_PORT})"
    docker compose -p "$MOCK_JWKS_COMPOSE_PROJECT_NAME" -f "${REPO_ROOT}/infra/testing/compose.yml" up -d --build >/dev/null

    local attempt
    for attempt in $(seq 1 30); do
        if curl -sf "${MOCK_JWKS_URL}/.well-known/openid-configuration" >/dev/null 2>&1; then
            echo "mock-jwks: outcome=[READY] url=[${MOCK_JWKS_URL}]"
            return 0
        fi
        sleep 1
    done
    echo "mock-jwks: outcome=[NOT_READY] gave up after 30s" >&2
    return 1
}

stop_mock_jwks() {
    echo "mock-jwks: tearing down infra/testing/compose.yml"
    docker compose -p "$MOCK_JWKS_COMPOSE_PROJECT_NAME" -f "${REPO_ROOT}/infra/testing/compose.yml" down >/dev/null 2>&1 || true
}

# require_mock_jwks_running - scripts/seed.sh and scripts/verify-seed.sh call this
# instead of starting mock-jwks themselves (finding 1/3): mock-jwks' lifecycle belongs
# solely to whichever script started the session (scripts/seed-demo-data.sh), so a
# second, independent start/stop here would risk exactly the same-kid-different-key
# problem finding 3 reproduced. Fails loudly with a clear pointer if it's not up.
require_mock_jwks_running() {
    if curl -sf "${MOCK_JWKS_URL}/.well-known/openid-configuration" >/dev/null 2>&1; then
        return 0
    fi
    echo "mock-jwks is not running at ${MOCK_JWKS_URL}." >&2
    echo "This script never starts/stops mock-jwks itself (review iteration 1, finding 3) -" >&2
    echo "run scripts/seed-demo-data.sh instead, which owns its lifecycle for the whole" >&2
    echo "seed+verify session, or start it yourself first with:" >&2
    echo "  docker compose -p ${MOCK_JWKS_COMPOSE_PROJECT_NAME} -f infra/testing/compose.yml up -d --build" >&2
    return 1
}

# mint_token <role> <oid> - prints the raw JWT (and only the JWT) on stdout; every log
# line about the mint itself goes to stderr and never includes the token value (§7 A09).
mint_token() {
    local role="$1"
    local oid="$2"
    local response
    response="$(curl -sf -X POST "${MOCK_JWKS_URL}/mint" \
        -H "Content-Type: application/json" \
        -d "{\"role\":\"${role}\",\"oid\":\"${oid}\"}")"
    echo "mint_token: outcome=[MINTED] role=[${role}] oid=[${oid}]" >&2
    echo "$response" | jq -r '.token'
}
