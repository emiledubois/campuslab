#!/usr/bin/env bash
# docs/designs/aws-deployment.md Part 4 - proves scripts/verify-deploy.sh's own
# request/response/reporting logic against infra/testing/stub-deploy/, a local Flask
# double - never against AWS/Entra, never mints a real-looking JWT (the "tampered"
# negative-auth variant fed to scripts/verify-deploy.sh is derived by that script's own
# logic from the one fixed, non-JWT-shaped token string this harness supplies). This
# scenario table IS acceptance criterion 6 - every row is actually exercised and
# asserted below, not just plausible.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

STUB_DEPLOY_PORT="${STUB_DEPLOY_PORT:-1081}"
STUB_EXPECTED_TOKEN="stub-fixed-token-for-selftest-only"
export STUB_DEPLOY_PORT STUB_EXPECTED_TOKEN
STUB_COMPOSE_PROJECT_NAME="campuslab-stub-deploy"
STUB_BASE_URL="http://localhost:${STUB_DEPLOY_PORT}"
CONNECT_TIMEOUT="${VERIFY_DEPLOY_CONNECT_TIMEOUT_SECONDS:-3}"

FAILURES=0

fail() {
    echo "verify-deploy-selftest: FAIL - $1" >&2
    FAILURES=$((FAILURES + 1))
}

start_stub() {
    echo "verify-deploy-selftest: starting infra/testing/stub-deploy-compose.yml (host port ${STUB_DEPLOY_PORT})"
    docker compose -p "$STUB_COMPOSE_PROJECT_NAME" -f "${REPO_ROOT}/infra/testing/stub-deploy-compose.yml" up -d --build >/dev/null

    local attempt
    for attempt in $(seq 1 30); do
        if curl -sf "${STUB_BASE_URL}/actuator/health" >/dev/null 2>&1; then
            echo "verify-deploy-selftest: outcome=[STUB_READY] url=[${STUB_BASE_URL}]"
            return 0
        fi
        sleep 1
    done
    echo "verify-deploy-selftest: outcome=[STUB_NOT_READY] gave up after 30s" >&2
    return 1
}

stop_stub() {
    echo "verify-deploy-selftest: tearing down infra/testing/stub-deploy-compose.yml"
    docker compose -p "$STUB_COMPOSE_PROJECT_NAME" -f "${REPO_ROOT}/infra/testing/stub-deploy-compose.yml" down >/dev/null 2>&1 || true
}

set_mode() {
    local mode="$1"
    curl -sf -X POST "${STUB_BASE_URL}/control/mode" -H "Content-Type: application/json" \
        -d "$(jq -n --arg mode "$mode" '{mode: $mode}')" >/dev/null
}

run_verify_deploy() {
    local base_url="$1"
    "${REPO_ROOT}/scripts/verify-deploy.sh" "$base_url" "$STUB_EXPECTED_TOKEN"
}

assert_exit_code() {
    local scenario="$1" expected="$2" actual="$3"
    if [ "$actual" != "$expected" ]; then
        fail "${scenario}: expected exit code ${expected}, got ${actual}"
    else
        echo "verify-deploy-selftest: scenario=[${scenario}] exitCode=[${actual}] outcome=[MATCH]"
    fi
}

assert_contains() {
    local scenario="$1" needle="$2" output="$3"
    if echo "$output" | grep -qF -- "$needle"; then
        echo "verify-deploy-selftest: scenario=[${scenario}] found=[${needle}]"
    else
        fail "${scenario}: expected output to contain '${needle}', it did not"
    fi
}

assert_not_contains() {
    local scenario="$1" needle="$2" output="$3"
    if echo "$output" | grep -qF -- "$needle"; then
        fail "${scenario}: expected output NOT to contain '${needle}', but it did"
    else
        echo "verify-deploy-selftest: scenario=[${scenario}] absent=[${needle}]"
    fi
}

assert_fail_count() {
    local scenario="$1" expected="$2" output="$3"
    local actual
    actual="$(echo "$output" | grep -c 'result=\[FAIL\]')"
    if [ "$actual" != "$expected" ]; then
        fail "${scenario}: expected ${expected} FAIL line(s), got ${actual}"
    else
        echo "verify-deploy-selftest: scenario=[${scenario}] failLines=[${actual}] outcome=[MATCH]"
    fi
}

# Row 1: run against http://localhost:1, stub not even involved - fails within
# VERIFY_DEPLOY_CONNECT_TIMEOUT_SECONDS, no hang.
scenario_unreachable() {
    local scenario="unreachable-host"
    local start_ts end_ts elapsed output exit_code
    start_ts="$(date +%s)"
    output="$(run_verify_deploy "http://localhost:1")"
    exit_code=$?
    end_ts="$(date +%s)"
    elapsed=$((end_ts - start_ts))
    assert_exit_code "$scenario" 2 "$exit_code"
    if [ "$elapsed" -gt $((CONNECT_TIMEOUT + 5)) ]; then
        fail "${scenario}: took ${elapsed}s, expected to fail fast (no hang)"
    else
        echo "verify-deploy-selftest: scenario=[${scenario}] elapsedSeconds=[${elapsed}] outcome=[NO_HANG]"
    fi
    assert_contains "$scenario" "check=[connectivity] result=[FAIL]" "$output"
}

# Row 2: healthy - all checks PASS, zero FAIL.
scenario_healthy() {
    local scenario="healthy"
    set_mode healthy
    local output exit_code
    output="$(run_verify_deploy "$STUB_BASE_URL")"
    exit_code=$?
    assert_exit_code "$scenario" 0 "$exit_code"
    assert_fail_count "$scenario" 0 "$output"
}

# Row 3: unhealthy - check 0 fails, checks 1-8 never run (fail-fast).
scenario_unhealthy() {
    local scenario="unhealthy"
    set_mode unhealthy
    local output exit_code
    output="$(run_verify_deploy "$STUB_BASE_URL")"
    exit_code=$?
    assert_exit_code "$scenario" 2 "$exit_code"
    assert_contains "$scenario" "check=[connectivity] result=[FAIL]" "$output"
    assert_not_contains "$scenario" "check=[catalog]" "$output"
    assert_not_contains "$scenario" "check=[bookings]" "$output"
    set_mode healthy
}

# Row 4: catalog-down - check 1 FAIL, checks 2-8 still all run and reported
# (continue-and-report-all is not silently short-circuited by one failure - AC12).
scenario_catalog_down() {
    local scenario="catalog-down"
    set_mode catalog-down
    local output exit_code
    output="$(run_verify_deploy "$STUB_BASE_URL")"
    exit_code=$?
    assert_exit_code "$scenario" 3 "$exit_code"
    assert_contains "$scenario" "check=[catalog] result=[FAIL]" "$output"
    for name in bookings audit report mq-admin notify kafka-admin auth-missing auth-empty auth-malformed auth-tampered; do
        assert_contains "$scenario" "check=[${name}]" "$output"
    done
    set_mode healthy
}

# Row 5: mq-missing-queue - check 5 FAIL, all others PASS.
scenario_mq_missing_queue() {
    local scenario="mq-missing-queue"
    set_mode mq-missing-queue
    local output exit_code
    output="$(run_verify_deploy "$STUB_BASE_URL")"
    exit_code=$?
    assert_exit_code "$scenario" 3 "$exit_code"
    assert_contains "$scenario" "check=[mq-admin] result=[FAIL]" "$output"
    assert_fail_count "$scenario" 1 "$output"
    set_mode healthy
}

# Row 6: mq-notify-down - check 6 WARN, everything else PASS - a WARN alone must not
# fail the run (AC13).
scenario_mq_notify_down() {
    local scenario="mq-notify-down"
    set_mode mq-notify-down
    local output exit_code
    output="$(run_verify_deploy "$STUB_BASE_URL")"
    exit_code=$?
    assert_exit_code "$scenario" 0 "$exit_code"
    assert_contains "$scenario" "check=[notify] result=[WARN]" "$output"
    assert_fail_count "$scenario" 0 "$output"
    set_mode healthy
}

# Row 7: kafka-drift - check 7 FAIL.
scenario_kafka_drift() {
    local scenario="kafka-drift"
    set_mode kafka-drift
    local output exit_code
    output="$(run_verify_deploy "$STUB_BASE_URL")"
    exit_code=$?
    assert_exit_code "$scenario" 3 "$exit_code"
    assert_contains "$scenario" "check=[kafka-admin] result=[FAIL]" "$output"
    set_mode healthy
}

main() {
    trap stop_stub EXIT

    if ! start_stub; then
        fail "stub never became healthy - aborting"
        exit 1
    fi

    scenario_unreachable
    scenario_healthy
    scenario_unhealthy
    scenario_catalog_down
    scenario_mq_missing_queue
    scenario_mq_notify_down
    scenario_kafka_drift

    if [ "$FAILURES" -gt 0 ]; then
        echo "verify-deploy-selftest: outcome=[FAILED] failureCount=[${FAILURES}]" >&2
        exit 1
    fi
    echo "verify-deploy-selftest: outcome=[PASSED] every row of Part 4's scenario table matched"
}

main "$@"
