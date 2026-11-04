package cl.campuslab.report.messaging;

import java.time.Instant;

/**
 * Copia propia de report del evento derivado de {@code audit.timeline}
 * (audit-timeline-compacted.md §5.4.1), nunca compartida con audit. {@code type} es
 * {@code String} crudo para que un tipo desconocido se clasifique como
 * {@code UNRECOGNIZED_TYPE} y no como fallo de deserializacion.
 */
public record BookingStateSnapshotEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        BookingStateSnapshotPayload payload) {

    public static final String TYPE = "BOOKING_STATE_SNAPSHOT";

    public boolean hasAllRequiredFields() {
        return type != null && eventId != null && timestamp != null && traceId != null && correlationId != null
                && payload != null && payload.bookingId() != null && payload.resourceId() != null
                && payload.currentStatus() != null && payload.lastTransitionAt() != null;
    }
}
