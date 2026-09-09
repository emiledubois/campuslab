CREATE TABLE booking (
    id                UUID PRIMARY KEY,
    resource_id       UUID NOT NULL,
    student_sub       VARCHAR(255) NOT NULL,
    requested_start   TIMESTAMPTZ NOT NULL,
    requested_end     TIMESTAMPTZ NOT NULL,
    notes             VARCHAR(500),
    status            VARCHAR(20) NOT NULL DEFAULT 'SOLICITADA',
    version           BIGINT NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_booking_status CHECK (
        status IN ('SOLICITADA', 'APROBADA', 'EN_PREPARACION', 'EN_USO', 'DEVUELTA', 'CANCELADA')
    ),
    CONSTRAINT chk_booking_window CHECK (requested_end > requested_start)
);

CREATE INDEX idx_booking_student_sub ON booking (student_sub);
CREATE INDEX idx_booking_status ON booking (status);
CREATE INDEX idx_booking_requested_start ON booking (requested_start);
