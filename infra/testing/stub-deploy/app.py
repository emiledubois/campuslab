"""Minimal standalone deployment test double (docs/designs/aws-deployment.md Part 4).

Stands in for "the deployed BFF, mq-admin, and kafka-admin" so
scripts/verify-deploy-selftest.sh can prove scripts/verify-deploy.sh's own
request/response/reporting logic without AWS or Entra - never for Entra itself, no JWT
is ever parsed here. Built the same way infra/testing/mock-jwks/app.py already is: a
small, single-purpose Flask double with its own Dockerfile/requirements.txt, never
merged into scripts/cold-start.sh or any real profile.

Auth here is plain string equality against STUB_EXPECTED_TOKEN, never JWT decoding of
any kind (that would defeat the point - this stub represents the already-authenticated
BFF's downstream endpoints, and the negative-auth cases scripts/verify-deploy.sh itself
exercises are only ever run for real against the real BFF/Entra).
"""

import os
import re

from flask import Flask, jsonify, request

STUB_EXPECTED_TOKEN = os.environ.get("STUB_EXPECTED_TOKEN", "stub-fixed-token-for-selftest-only")

# A08: /control/mode's only deserialized field, checked against a fixed allow-list -
# never reflectively bound, never used to build a path/SQL string/shell command (same
# pattern as mock-jwks' role/oid check).
ALLOWED_MODES = {
    "healthy",
    "unhealthy",
    "catalog-down",
    "mq-missing-queue",
    "mq-notify-down",
    "kafka-drift",
}

QUEUE_NAMES = [
    "q.cmd.email",
    "q.cmd.email.dlq",
    "q.cmd.prep",
    "q.cmd.prep.dlq",
    "q.cmd.voucher",
    "q.cmd.voucher.dlq",
]
DLQ_NAMES = {"q.cmd.email.dlq", "q.cmd.prep.dlq", "q.cmd.voucher.dlq"}
TOPIC_NAMES = ["bookings.events", "audit.timeline"]

# docs/designs/mq-admin-endpoints.md (Slice A) - the exchange names RabbitTopologyConfig
# declares. Mirrors QUEUE_NAMES' role: any create/delete/purge/binding call touching one
# of these (or a name in QUEUE_NAMES) must be rejected the same way the real
# RabbitAdminService rejects it, never silently allowed.
EXCHANGE_NAMES = {"cmd.direct", "cmd.topic", "cmd.dead.dlx"}
NAME_PATTERN = re.compile(r"^(?!amq\.)[A-Za-z0-9_.:-]{1,255}$")
EXCHANGE_TYPES = {"direct", "topic", "fanout", "headers"}
PROTECTED_DETAIL = "is managed by the base RabbitMQ topology and must not be modified via this endpoint"

app = Flask(__name__)
state = {"mode": "healthy"}


def _authorized() -> bool:
    return request.headers.get("Authorization", "") == f"Bearer {STUB_EXPECTED_TOKEN}"


@app.get("/actuator/health")
def health():
    if state["mode"] == "unhealthy":
        return jsonify({"status": "DOWN"}), 503
    return jsonify({"status": "UP"})


@app.get("/api/catalog/resources")
def catalog_resources():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    if state["mode"] == "catalog-down":
        return jsonify({"error": "catalog unavailable"}), 500
    return jsonify([])


@app.get("/api/bookings")
def bookings():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    return jsonify([])


@app.get("/api/audit/timeline")
def audit_timeline():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    return jsonify([])


@app.get("/api/report/kpis")
def report_kpis():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    return jsonify({})


@app.get("/api/admin/mq/queues")
def mq_queues():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    names = list(QUEUE_NAMES)
    if state["mode"] == "mq-missing-queue":
        names = [name for name in names if name != "q.cmd.voucher.dlq"]
    zeroed = state["mode"] == "mq-notify-down"
    queues = []
    for name in names:
        is_dlq = name in DLQ_NAMES
        consumer_count = 0 if (zeroed or is_dlq) else 1
        queues.append(
            {
                "name": name,
                "isDlq": is_dlq,
                "messageCount": 0,
                "consumerCount": consumer_count,
                "dlqRatePerMinute": 0.0 if is_dlq else None,
            }
        )
    return jsonify(queues)


@app.get("/api/admin/kafka/topics")
def kafka_topics():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    topics = []
    for name in TOPIC_NAMES:
        config_matches = not (state["mode"] == "kafka-drift" and name == "audit.timeline")
        topics.append(
            {
                "name": name,
                "expectedPartitions": 3,
                "actualPartitions": 3,
                "expectedReplicationFactor": 1,
                "actualReplicationFactor": 1,
                "expectedCleanupPolicy": "delete",
                "actualCleanupPolicy": "delete",
                "expectedRetentionMs": 604800000,
                "actualRetentionMs": 604800000,
                "configMatches": config_matches,
            }
        )
    return jsonify(topics)


def _protected_response(name: str):
    return jsonify({"detail": f"'{name}' {PROTECTED_DETAIL}"}), 409


# docs/designs/mq-admin-endpoints.md (Slice A) - the seven new admin endpoints, mirrored
# here only closely enough to prove scripts/verify-deploy.sh's own request/response
# handling (status codes, body shapes it reads) against a stub, never as a real
# RabbitMQ double - no state is actually created/destroyed, no mode in ALLOWED_MODES
# changes this behaviour (these routes don't participate in the health/topology/drift
# scenario table at all).
@app.post("/api/admin/mq/queues")
def create_queue():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    body = request.get_json(silent=True) or {}
    name = body.get("name") or ""
    if not NAME_PATTERN.match(name):
        return jsonify({"detail": "name: must not be blank"}), 400
    if name in QUEUE_NAMES:
        return _protected_response(name)
    return (
        jsonify(
            {
                "name": name,
                "durable": True,
                "exclusive": False,
                "autoDelete": False,
                "messageCount": 0,
                "consumerCount": 0,
            }
        ),
        201,
    )


@app.delete("/api/admin/mq/queues/<name>")
def delete_queue(name):
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    if name in QUEUE_NAMES:
        return _protected_response(name)
    return "", 204


@app.post("/api/admin/mq/queues/<name>/purge")
def purge_queue(name):
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    if name in QUEUE_NAMES:
        return _protected_response(name)
    return jsonify({"purgedMessageCount": 0}), 200


@app.post("/api/admin/mq/exchanges")
def create_exchange():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    body = request.get_json(silent=True) or {}
    name = body.get("name") or ""
    exchange_type = body.get("type") or ""
    if not NAME_PATTERN.match(name):
        return jsonify({"detail": "name: must not be blank"}), 400
    if exchange_type not in EXCHANGE_TYPES:
        return jsonify({"detail": "type: must be one of: direct, topic, fanout, headers"}), 400
    if name in EXCHANGE_NAMES:
        return _protected_response(name)
    return jsonify({"name": name, "type": exchange_type, "durable": True, "autoDelete": False}), 201


@app.delete("/api/admin/mq/exchanges/<name>")
def delete_exchange(name):
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    if name in EXCHANGE_NAMES:
        return _protected_response(name)
    return "", 204


def _binding_request_body():
    body = request.get_json(silent=True) or {}
    return body.get("source") or "", body.get("destination") or ""


@app.post("/api/admin/mq/bindings")
def create_binding():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    source, destination = _binding_request_body()
    if not NAME_PATTERN.match(source) or not NAME_PATTERN.match(destination):
        return jsonify({"detail": "source/destination: must not be blank"}), 400
    if source in EXCHANGE_NAMES or destination in QUEUE_NAMES:
        return _protected_response(f"{source} -> {destination}")
    body = request.get_json(silent=True) or {}
    return (
        jsonify(
            {
                "source": source,
                "destination": destination,
                "destinationType": body.get("destinationType", "QUEUE"),
                "routingKey": body.get("routingKey"),
            }
        ),
        201,
    )


@app.delete("/api/admin/mq/bindings")
def delete_binding():
    if not _authorized():
        return jsonify({"error": "unauthorized"}), 401
    source, destination = _binding_request_body()
    if source in EXCHANGE_NAMES or destination in QUEUE_NAMES:
        return _protected_response(f"{source} -> {destination}")
    return "", 204


@app.post("/control/mode")
def control_mode():
    body = request.get_json(silent=True) or {}
    mode = body.get("mode")
    if mode not in ALLOWED_MODES:
        return jsonify({"error": f"mode must be one of {sorted(ALLOWED_MODES)}"}), 400
    state["mode"] = mode
    print(f"stub-deploy: outcome=[MODE_SET] mode=[{mode}]", flush=True)
    return jsonify({"mode": mode})


if __name__ == "__main__":
    port = int(os.environ.get("STUB_DEPLOY_PORT", "1081"))
    app.run(host="0.0.0.0", port=port)
