#!/usr/bin/env bash
# docs/designs/aws-deployment.md Part 4 - a single command a human runs against a live
# deployment (direct BFF or the API Gateway invoke URL) to sanity-check all eight
# services' health, RabbitMQ/Kafka topology, structural negative-auth cases, and (since
# docs/designs/mq-admin-endpoints.md, Slice A) the admin create/delete/purge endpoints -
# before trusting it in front of the class.
#
# Checks 0-8 are read-only by construction (A04): every one of them is a GET, no
# state-changing call is ever made. Checks 9-11 are a deliberate, documented exception:
# the only way to verify a POST/DELETE endpoint actually works is to call it. Every
# resource these checks create, they also delete before returning (confirmed via each
# step's own HTTP status, never assumed), using a timestamped, obviously-disposable
# probe name that can't collide with anything the demo/case topology declares.
#
# This script never mints, fetches, or derives the bearer token from anything other
# than its own second CLI argument (§7 A02/A09) - obtaining a real one (a real login,
# or mock-jwks' mint_token run by hand for a non-production target) is entirely the
# human's job. The token itself is never logged, echoed, or included in any output line.
set -uo pipefail

usage() {
    echo "usage: verify-deploy.sh <base-url> <bearer-token>" >&2
    echo "  base-url     e.g. http://<EIP-apps>:8080 or the API Gateway invoke URL - required, no default" >&2
    echo "  bearer-token a raw JWT (no 'Bearer ' prefix) carrying the ADMIN role - required, no default" >&2
    echo "env overrides (both optional): VERIFY_DEPLOY_CONNECT_TIMEOUT_SECONDS (default 3)," >&2
    echo "  VERIFY_DEPLOY_MAX_TIME_SECONDS (default 6)" >&2
}

if [ "$#" -ne 2 ]; then
    usage
    exit 1
fi

BASE_URL="$1"
TOKEN="$2"

if [ -z "$BASE_URL" ] || [ -z "$TOKEN" ]; then
    usage
    exit 1
fi

CONNECT_TIMEOUT="${VERIFY_DEPLOY_CONNECT_TIMEOUT_SECONDS:-3}"
MAX_TIME="${VERIFY_DEPLOY_MAX_TIME_SECONDS:-6}"

PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

# HTTP_STATUS/HTTP_BODY are set by http_get - a plain pair of globals, not worth a
# struct/array for a script this size (CLAUDE.md: three similar lines beat a premature
# abstraction).
HTTP_STATUS=""
HTTP_BODY=""

log_check() {
    local name="$1" result="$2" detail="$3"
    echo "verify-deploy: check=[${name}] result=[${result}] detail=[${detail}]"
    case "$result" in
        PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
        FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
        WARN) WARN_COUNT=$((WARN_COUNT + 1)) ;;
    esac
}

print_outcome() {
    local outcome
    if [ "$FAIL_COUNT" -gt 0 ]; then outcome=FAILED; else outcome=PASSED; fi
    echo "verify-deploy: outcome=[${outcome}] pass=[${PASS_COUNT}] fail=[${FAIL_COUNT}] warn=[${WARN_COUNT}]"
}

# http_get <url> <auth-header-or-empty> - sets HTTP_STATUS/HTTP_BODY on stdout success,
# returns 1 (HTTP_STATUS/HTTP_BODY left stale) if curl itself could not complete the
# request at all (connection refused/timed out) - the two failure modes this script
# must tell apart (A04: never hang past VERIFY_DEPLOY_*_SECONDS).
http_get() {
    local url="$1" auth_header="$2"
    local -a curl_args=(-sS -w '\n%{http_code}' --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" "$url")
    if [ -n "$auth_header" ]; then
        curl_args+=(-H "$auth_header")
    fi
    local response
    response="$(curl "${curl_args[@]}" 2>/dev/null)"
    local curl_exit=$?
    if [ "$curl_exit" -ne 0 ]; then
        return 1
    fi
    HTTP_STATUS="$(echo "$response" | tail -n1)"
    HTTP_BODY="$(echo "$response" | sed '$d')"
    return 0
}

auth_header_for_token() {
    echo "Authorization: Bearer $1"
}

# http_call <method> <path> <json-body-or-empty> - like http_get but for the admin
# endpoints' POST/DELETE verbs, same connectivity/timeout contract and the same
# HTTP_STATUS/HTTP_BODY globals. Always sends the bearer token - every endpoint this is
# used against is ADMIN-only.
http_call() {
    local method="$1" path="$2" body="$3"
    local url="${BASE_URL}${path}"
    local -a curl_args=(-sS -w '\n%{http_code}' --connect-timeout "$CONNECT_TIMEOUT" --max-time "$MAX_TIME" \
        -X "$method" -H "$(auth_header_for_token "$TOKEN")" "$url")
    if [ -n "$body" ]; then
        curl_args+=(-H 'Content-Type: application/json' -d "$body")
    fi
    local response
    response="$(curl "${curl_args[@]}" 2>/dev/null)"
    local curl_exit=$?
    if [ "$curl_exit" -ne 0 ]; then
        return 1
    fi
    HTTP_STATUS="$(echo "$response" | tail -n1)"
    HTTP_BODY="$(echo "$response" | sed '$d')"
    return 0
}

# Check 0 - connectivity/BFF health. Fail here aborts immediately with exit 2: there is
# no value in running the remaining checks, each with their own timeout, against a host
# that already didn't answer the first one.
check_connectivity() {
    local url="${BASE_URL}/actuator/health"
    if ! http_get "$url" ""; then
        log_check "connectivity" "FAIL" "base URL ${BASE_URL} is unreachable (connection failed or timed out within ${CONNECT_TIMEOUT}s)"
        print_outcome
        exit 2
    fi
    if [ "$HTTP_STATUS" = "200" ] && echo "$HTTP_BODY" | grep -q '"status":"UP"'; then
        log_check "connectivity" "PASS" "base URL ${BASE_URL}: GET /actuator/health returned 200 UP"
    else
        log_check "connectivity" "FAIL" "base URL ${BASE_URL}: GET /actuator/health returned httpStatus=${HTTP_STATUS}, expected 200 with status UP"
        print_outcome
        exit 2
    fi
}

# Checks 1-3 - catalog/bookings/audit, all shaped the same: 200 + a JSON array.
check_json_array_endpoint() {
    local name="$1" path="$2"
    local url="${BASE_URL}${path}"
    if ! http_get "$url" "$(auth_header_for_token "$TOKEN")"; then
        log_check "$name" "FAIL" "GET ${path} unreachable or timed out"
        return
    fi
    if [ "$HTTP_STATUS" = "200" ] && echo "$HTTP_BODY" | jq -e 'type == "array"' >/dev/null 2>&1; then
        log_check "$name" "PASS" "GET ${path} returned 200 with a JSON array"
    else
        log_check "$name" "FAIL" "GET ${path} returned httpStatus=${HTTP_STATUS}, expected 200 with a JSON array"
    fi
}

# Check 4 - report KPIs: 200 + a JSON object.
check_report() {
    local path="/api/report/kpis?range=last24h"
    local url="${BASE_URL}${path}"
    if ! http_get "$url" "$(auth_header_for_token "$TOKEN")"; then
        log_check "report" "FAIL" "GET ${path} unreachable or timed out"
        return
    fi
    if [ "$HTTP_STATUS" = "200" ] && echo "$HTTP_BODY" | jq -e 'type == "object"' >/dev/null 2>&1; then
        log_check "report" "PASS" "GET ${path} returned 200 with a JSON object"
    else
        log_check "report" "FAIL" "GET ${path} returned httpStatus=${HTTP_STATUS}, expected 200 with a JSON object"
    fi
}

# Check 5 - mq-admin + RabbitMQ topology: 200 + all six expected queue names present.
# Populates MQ_QUEUES_BODY for check 6 to reuse (design's own "from the same response").
MQ_QUEUES_BODY=""

check_mq() {
    local path="/api/admin/mq/queues"
    local url="${BASE_URL}${path}"
    if ! http_get "$url" "$(auth_header_for_token "$TOKEN")"; then
        log_check "mq-admin" "FAIL" "GET ${path} unreachable or timed out"
        return
    fi
    if [ "$HTTP_STATUS" != "200" ] || ! echo "$HTTP_BODY" | jq -e 'type == "array"' >/dev/null 2>&1; then
        log_check "mq-admin" "FAIL" "GET ${path} returned httpStatus=${HTTP_STATUS}, expected 200 with a JSON array"
        return
    fi
    MQ_QUEUES_BODY="$HTTP_BODY"
    local expected=(q.cmd.email q.cmd.email.dlq q.cmd.prep q.cmd.prep.dlq q.cmd.voucher q.cmd.voucher.dlq)
    local missing=() name
    for name in "${expected[@]}"; do
        if ! echo "$MQ_QUEUES_BODY" | jq -e --arg n "$name" 'any(.[]; .name == $n)' >/dev/null 2>&1; then
            missing+=("$name")
        fi
    done
    if [ "${#missing[@]}" -eq 0 ]; then
        log_check "mq-admin" "PASS" "all six expected queue names present"
    else
        log_check "mq-admin" "FAIL" "missing queue name(s): ${missing[*]}"
    fi
}

# Check 6 - notify liveness, indirect/best-effort: WARN (never FAIL) if q.cmd.email or
# q.cmd.prep's consumerCount is 0 - notify has no HTTP surface of its own, this is only
# an approximation.
check_notify() {
    if [ -z "$MQ_QUEUES_BODY" ]; then
        log_check "notify" "FAIL" "cannot evaluate consumer counts - check 5's mq-admin topology response was unusable"
        return
    fi
    local email_cc prep_cc
    email_cc="$(echo "$MQ_QUEUES_BODY" | jq -r '[.[] | select(.name == "q.cmd.email")][0].consumerCount // "missing"')"
    prep_cc="$(echo "$MQ_QUEUES_BODY" | jq -r '[.[] | select(.name == "q.cmd.prep")][0].consumerCount // "missing"')"
    if [ "$email_cc" = "0" ] || [ "$prep_cc" = "0" ]; then
        log_check "notify" "WARN" "consumerCount is 0 on q.cmd.email and/or q.cmd.prep (email=${email_cc}, prep=${prep_cc}) - notify may be down, not a guarantee"
    else
        log_check "notify" "PASS" "consumerCount > 0 on both q.cmd.email (${email_cc}) and q.cmd.prep (${prep_cc})"
    fi
}

# Check 7 - kafka-admin + Kafka topics: 200 + bookings.events/audit.timeline present,
# each with configMatches true. A drift (configMatches: false) is a hard FAIL.
check_kafka() {
    local path="/api/admin/kafka/topics"
    local url="${BASE_URL}${path}"
    if ! http_get "$url" "$(auth_header_for_token "$TOKEN")"; then
        log_check "kafka-admin" "FAIL" "GET ${path} unreachable or timed out"
        return
    fi
    if [ "$HTTP_STATUS" != "200" ] || ! echo "$HTTP_BODY" | jq -e 'type == "array"' >/dev/null 2>&1; then
        log_check "kafka-admin" "FAIL" "GET ${path} returned httpStatus=${HTTP_STATUS}, expected 200 with a JSON array"
        return
    fi
    local problems=() name present matches
    for name in bookings.events audit.timeline; do
        present="$(echo "$HTTP_BODY" | jq -r --arg n "$name" '[.[] | select(.name == $n)] | length')"
        if [ "$present" = "0" ]; then
            problems+=("${name} missing")
            continue
        fi
        matches="$(echo "$HTTP_BODY" | jq -r --arg n "$name" '[.[] | select(.name == $n)][0].configMatches')"
        if [ "$matches" != "true" ]; then
            problems+=("${name} configMatches=${matches}")
        fi
    done
    if [ "${#problems[@]}" -eq 0 ]; then
        log_check "kafka-admin" "PASS" "bookings.events and audit.timeline present with configMatches=true"
    else
        log_check "kafka-admin" "FAIL" "topic drift or missing topic: ${problems[*]}"
    fi
}

# tamper_signature <token> - the real token with its last 8 signature characters
# replaced (still three dot-separated segments, decodable header/payload, invalid
# signature) - used only for negative-auth case 4. Never mints or derives a new token
# from scratch, only mutates the one it was given.
tamper_signature() {
    local token="$1"
    local header payload signature
    header="$(echo "$token" | cut -d. -f1)"
    payload="$(echo "$token" | cut -d. -f2)"
    signature="$(echo "$token" | cut -d. -f3)"
    local sig_len=${#signature}
    if [ "$sig_len" -lt 8 ]; then
        echo "${header}.${payload}.${signature}tampered00"
        return
    fi
    local prefix="${signature:0:sig_len-8}"
    local tail="${signature: -8}"
    local reversed
    reversed="$(echo "$tail" | rev)"
    if [ "$reversed" = "$tail" ]; then
        reversed="00000000"
    fi
    echo "${header}.${payload}.${prefix}${reversed}"
}

# evaluate_negative_case <check-name> <url> <auth-header-or-empty> <description> -
# expects 401. A 403 is scored FAIL (would mean authenticated-but-under-privileged,
# the wrong failure mode for a missing/garbage/tampered credential).
evaluate_negative_case() {
    local check_name="$1" url="$2" auth_header="$3" description="$4"
    if ! http_get "$url" "$auth_header"; then
        log_check "$check_name" "FAIL" "${description} -> unreachable or timed out"
        return
    fi
    case "$HTTP_STATUS" in
        401) log_check "$check_name" "PASS" "${description} -> 401" ;;
        403) log_check "$check_name" "FAIL" "${description} -> 403 (should be 401 - wrong failure mode)" ;;
        *) log_check "$check_name" "FAIL" "${description} -> httpStatus=${HTTP_STATUS}, expected 401" ;;
    esac
}

# Check 8 - four structural negative-auth cases, all against the same
# already-proven-reachable GET {base}/api/catalog/resources (from check 1) so a 401
# here is unambiguous. These are structural (malformed/absent/tampered variants of the
# one real token given), not the classic expired/wrong-audience/wrong-role set, which
# needs genuinely distinct tokens this script cannot mint - that set is HUMAN-ONLY.
check_negative_auth() {
    local url="${BASE_URL}/api/catalog/resources"
    evaluate_negative_case "auth-missing" "$url" "" "no Authorization header"
    evaluate_negative_case "auth-empty" "$url" "Authorization: Bearer " "empty Bearer value"
    evaluate_negative_case "auth-malformed" "$url" "Authorization: Bearer not-a-jwt-shaped-string" "malformed bearer value"
    local tampered
    tampered="$(tamper_signature "$TOKEN")"
    evaluate_negative_case "auth-tampered" "$url" "Authorization: Bearer ${tampered}" "tampered signature"
}

# Check 9 - admin create/delete lifecycle (docs/designs/mq-admin-endpoints.md): create a
# disposable queue+exchange+binding, then tear all three back down, each step verified by
# its own HTTP status. Uses a timestamped probe name so repeated runs never collide with
# a previous run's leftovers or with anything the real topology declares.
check_mq_admin_lifecycle() {
    local suffix queue exchange binding_body
    suffix="$(date +%s)"
    queue="verify-deploy.probe.queue.${suffix}"
    exchange="verify-deploy.probe.exchange.${suffix}"
    binding_body="{\"source\":\"${exchange}\",\"destination\":\"${queue}\",\"destinationType\":\"QUEUE\",\"routingKey\":\"probe\"}"

    if ! http_call POST "/api/admin/mq/queues" "{\"name\":\"${queue}\",\"durable\":true}"; then
        log_check "mq-admin-create-queue" "FAIL" "POST /api/admin/mq/queues unreachable or timed out"
    elif [ "$HTTP_STATUS" = "201" ]; then
        log_check "mq-admin-create-queue" "PASS" "created disposable queue ${queue}"
    else
        log_check "mq-admin-create-queue" "FAIL" "POST /api/admin/mq/queues returned httpStatus=${HTTP_STATUS}, expected 201"
    fi

    if ! http_call POST "/api/admin/mq/exchanges" "{\"name\":\"${exchange}\",\"type\":\"direct\"}"; then
        log_check "mq-admin-create-exchange" "FAIL" "POST /api/admin/mq/exchanges unreachable or timed out"
    elif [ "$HTTP_STATUS" = "201" ]; then
        log_check "mq-admin-create-exchange" "PASS" "created disposable exchange ${exchange}"
    else
        log_check "mq-admin-create-exchange" "FAIL" "POST /api/admin/mq/exchanges returned httpStatus=${HTTP_STATUS}, expected 201"
    fi

    if ! http_call POST "/api/admin/mq/bindings" "$binding_body"; then
        log_check "mq-admin-create-binding" "FAIL" "POST /api/admin/mq/bindings unreachable or timed out"
    elif [ "$HTTP_STATUS" = "201" ]; then
        log_check "mq-admin-create-binding" "PASS" "bound ${exchange} -> ${queue}"
    else
        log_check "mq-admin-create-binding" "FAIL" "POST /api/admin/mq/bindings returned httpStatus=${HTTP_STATUS}, expected 201"
    fi

    if ! http_call POST "/api/admin/mq/queues/${queue}/purge" ""; then
        log_check "mq-admin-purge-queue" "FAIL" "POST /api/admin/mq/queues/${queue}/purge unreachable or timed out"
    elif [ "$HTTP_STATUS" = "200" ]; then
        log_check "mq-admin-purge-queue" "PASS" "purged disposable queue ${queue}"
    else
        log_check "mq-admin-purge-queue" "FAIL" "POST /api/admin/mq/queues/${queue}/purge returned httpStatus=${HTTP_STATUS}, expected 200"
    fi

    # Teardown - attempted regardless of the create steps' outcomes above, so a probe
    # resource that *did* get created is never left behind just because a later create
    # step failed.
    if ! http_call DELETE "/api/admin/mq/bindings" "$binding_body"; then
        log_check "mq-admin-delete-binding" "FAIL" "DELETE /api/admin/mq/bindings unreachable or timed out"
    elif [ "$HTTP_STATUS" = "204" ]; then
        log_check "mq-admin-delete-binding" "PASS" "unbound ${exchange} -> ${queue}"
    else
        log_check "mq-admin-delete-binding" "FAIL" "DELETE /api/admin/mq/bindings returned httpStatus=${HTTP_STATUS}, expected 204"
    fi

    if ! http_call DELETE "/api/admin/mq/queues/${queue}" ""; then
        log_check "mq-admin-delete-queue" "FAIL" "DELETE /api/admin/mq/queues/${queue} unreachable or timed out"
    elif [ "$HTTP_STATUS" = "204" ]; then
        log_check "mq-admin-delete-queue" "PASS" "deleted disposable queue ${queue}"
    else
        log_check "mq-admin-delete-queue" "FAIL" "DELETE /api/admin/mq/queues/${queue} returned httpStatus=${HTTP_STATUS}, expected 204"
    fi

    if ! http_call DELETE "/api/admin/mq/exchanges/${exchange}" ""; then
        log_check "mq-admin-delete-exchange" "FAIL" "DELETE /api/admin/mq/exchanges/${exchange} unreachable or timed out"
    elif [ "$HTTP_STATUS" = "204" ]; then
        log_check "mq-admin-delete-exchange" "PASS" "deleted disposable exchange ${exchange}"
    else
        log_check "mq-admin-delete-exchange" "FAIL" "DELETE /api/admin/mq/exchanges/${exchange} returned httpStatus=${HTTP_STATUS}, expected 204"
    fi
}

# Check 10 - validation: an empty queue name must be rejected with 400, not accepted or
# turned into a 500.
check_mq_admin_validation() {
    if ! http_call POST "/api/admin/mq/queues" '{"name":""}'; then
        log_check "mq-admin-validation" "FAIL" "POST /api/admin/mq/queues (empty name) unreachable or timed out"
    elif [ "$HTTP_STATUS" = "400" ]; then
        log_check "mq-admin-validation" "PASS" "empty queue name rejected with 400"
    else
        log_check "mq-admin-validation" "FAIL" "POST /api/admin/mq/queues (empty name) returned httpStatus=${HTTP_STATUS}, expected 400"
    fi
}

# Check 11 - topology protection: deleting a name MqTopology declares (q.cmd.email) must
# be rejected with 409, never silently succeed. A 204 here is scored FAIL and is the one
# result in this whole script that means something is actually wrong with the live
# deployment's managed topology, not just with this check.
check_mq_admin_protection() {
    if ! http_call DELETE "/api/admin/mq/queues/q.cmd.email" ""; then
        log_check "mq-admin-protection" "FAIL" "DELETE /api/admin/mq/queues/q.cmd.email unreachable or timed out"
    elif [ "$HTTP_STATUS" = "409" ]; then
        log_check "mq-admin-protection" "PASS" "managed queue q.cmd.email correctly rejected with 409"
    else
        log_check "mq-admin-protection" "FAIL" "DELETE /api/admin/mq/queues/q.cmd.email returned httpStatus=${HTTP_STATUS}, expected 409 (if this was 204, the managed topology may actually have been deleted - check immediately)"
    fi
}

main() {
    check_connectivity

    check_json_array_endpoint "catalog" "/api/catalog/resources"
    check_json_array_endpoint "bookings" "/api/bookings"
    check_json_array_endpoint "audit" "/api/audit/timeline"
    check_report
    check_mq
    check_notify
    check_kafka
    check_negative_auth
    check_mq_admin_lifecycle
    check_mq_admin_validation
    check_mq_admin_protection

    print_outcome
    if [ "$FAIL_COUNT" -gt 0 ]; then
        exit 3
    fi
    exit 0
}

main
