CREATE TABLE report_event (
    id             UUID PRIMARY KEY,
    event_id       VARCHAR(64)  NOT NULL,
    booking_id     UUID         NOT NULL,
    resource_id    UUID         NOT NULL,
    event_type     VARCHAR(30)  NOT NULL,
    from_status    VARCHAR(20),
    to_status      VARCHAR(20)  NOT NULL,
    occurred_at    TIMESTAMPTZ  NOT NULL,
    received_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_report_event_event_id UNIQUE (event_id)
);

CREATE INDEX idx_report_event_event_type_occurred_at ON report_event (event_type, occurred_at);
CREATE INDEX idx_report_event_booking_id             ON report_event (booking_id);
CREATE INDEX idx_report_event_resource_id            ON report_event (resource_id);
