package cl.campuslab.bookings.messaging;

import java.time.Instant;

/**
 * The project-wide messaging envelope (CLAUDE.md/design doc §5.2), independent of
 * {@link NotificationEnvelope} - a Kafka failure and a RabbitMQ failure are unrelated
 * to each other (design doc §5.3), so the two envelopes are deliberately not shared.
 * {@code correlationId} is always the {@code bookingId} as a string; the Kafka message
 * key is that same value, guaranteeing per-booking ordering within a partition.
 */
public record BookingStreamEnvelope(
        BookingStreamEventType type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        BookingStreamPayload payload) {
}
