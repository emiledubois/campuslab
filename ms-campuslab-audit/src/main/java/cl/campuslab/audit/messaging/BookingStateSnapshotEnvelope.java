package cl.campuslab.audit.messaging;

import java.time.Instant;

/**
 * Evento derivado que audit publica en {@code audit.timeline} (audit-timeline-compacted.md
 * §5.3.1). Distinto de {@link BookingStreamEnvelope}: este es el estado actual completo de
 * una reserva, no un hecho.
 */
public record BookingStateSnapshotEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        BookingStateSnapshotPayload payload) {

    public static final String TYPE = "BOOKING_STATE_SNAPSHOT";
}
