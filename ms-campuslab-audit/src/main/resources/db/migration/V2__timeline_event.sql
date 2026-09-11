CREATE TABLE timeline_event (
    id             UUID PRIMARY KEY,
    event_id       VARCHAR(64)  NOT NULL,
    booking_id     UUID         NOT NULL,
    resource_id    UUID         NOT NULL,
    event_type     VARCHAR(30)  NOT NULL,
    actor_oid      VARCHAR(255) NOT NULL,
    actor_roles    VARCHAR(255) NOT NULL,
    student_oid    VARCHAR(255) NOT NULL,
    from_status    VARCHAR(20),
    to_status      VARCHAR(20)  NOT NULL,
    trace_id       VARCHAR(64)  NOT NULL,
    correlation_id VARCHAR(64)  NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    received_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_timeline_event_event_id UNIQUE (event_id)
);

CREATE INDEX idx_timeline_event_booking_id  ON timeline_event (booking_id);
CREATE INDEX idx_timeline_event_student_oid ON timeline_event (student_oid);
CREATE INDEX idx_timeline_event_actor_oid   ON timeline_event (actor_oid);
CREATE INDEX idx_timeline_event_event_type  ON timeline_event (event_type);
CREATE INDEX idx_timeline_event_occurred_at ON timeline_event (occurred_at);
