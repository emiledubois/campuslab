package cl.campuslab.report.messaging;

import java.time.Instant;

/**
 * The project-wide messaging envelope (CLAUDE.md), report's own copy (design doc §5.2).
 * {@code type} is deliberately a raw {@code String}, not an enum - an unrecognized
 * value must deserialize successfully so the listener can classify it as {@code
 * UNRECOGNIZED_TYPE} itself, rather than have Jackson reject it at the deserializer
 * level and get misclassified as {@code UNPARSEABLE_ENVELOPE}.
 */
public record BookingStreamEnvelope(
        String type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        BookingStreamPayload payload) {

    public boolean hasAllRequiredFields() {
        return type != null && eventId != null && timestamp != null && traceId != null && correlationId != null
                && payload != null && payload.bookingId() != null && payload.resourceId() != null
                && payload.studentOid() != null && payload.actorOid() != null && payload.toStatus() != null;
    }
}
