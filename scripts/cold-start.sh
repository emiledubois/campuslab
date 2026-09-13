#!/usr/bin/env bash
# Demo-readiness.md Decision 1 - the one command that starts the actual demo. Merges the
# three previously-independent `docker compose -f` invocations into a single project so
# `depends_on: condition: service_healthy` spans all of them, closing the startup-ordering
# race slice 5's QA report flagged (see infra/apps/compose.yml's depends_on graph).
#
# Deliberately never references infra/testing/compose.yml or its test-only JWKS double -
# see demo-readiness.md AC8, which greps this file and README.md for exactly that name.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

# Review iteration 1, finding 2: Compose's own project-naming default is derived from
# the first -f file's directory (here, infra/mq -> project "mq"), which produced
# container names (e.g. mq-catalog-db-1) that scripts/seed.sh's own hardcoded
# assumption (catalog-db) didn't match. Pinning an explicit, consistent project name
# here - and in every other script that touches these same containers
# (scripts/seed.sh, scripts/measure-cold-boot.sh, scripts/seed-demo-data.sh) - makes
# container names predictable regardless of which directory a script is invoked from.
export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-campuslab}"

if ! docker network inspect campuslab-net >/dev/null 2>&1; then
    echo "cold-start: creating one-time docker network campuslab-net"
    docker network create campuslab-net
fi

ENV_FILE="${ENV_FILE:-.env}"
if [ ! -f "$ENV_FILE" ]; then
    echo "cold-start: $ENV_FILE not found - copy .env.example to .env and fill it in first" >&2
    exit 1
fi

docker compose --env-file "$ENV_FILE" \
    -f infra/mq/compose.yml \
    -f infra/kafka/compose.yml \
    -f infra/apps/compose.yml \
    --profile local up -d --build
