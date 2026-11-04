-- Proyeccion del estado actual por reserva, alimentada solo desde audit.timeline.
-- Una fila por reserva. No guarda studentOid ni actorOid: la misma minimizacion que report_event.
CREATE TABLE booking_state_projection (
    booking_id          UUID         PRIMARY KEY,
    resource_id         UUID         NOT NULL,
    current_status      VARCHAR(20)  NOT NULL,
    last_transition_at  TIMESTAMPTZ  NOT NULL,
    last_event_id       VARCHAR(64)  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_booking_state_projection_last_event_id UNIQUE (last_event_id)
);

CREATE INDEX idx_booking_state_projection_current_status ON booking_state_projection (current_status);
