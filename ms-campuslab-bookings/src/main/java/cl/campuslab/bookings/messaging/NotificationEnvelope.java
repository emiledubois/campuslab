package cl.campuslab.bookings.messaging;

import java.time.Instant;

/**
 * The project-wide messaging envelope (CLAUDE.md/design doc §5.2) - {@code eventId} is
 * minted fresh per message (the idempotency key notify's own dedup cache is keyed on),
 * {@code traceId} is minted fresh per bookings HTTP request and shared across every
 * message published within that one request (design doc §5.2/§10 open question 5),
 * {@code correlationId} is always the {@code bookingId} as a string - the natural key
 * tying every message about one booking together.
 */
public record NotificationEnvelope(
        NotificationType type,
        String eventId,
        Instant timestamp,
        String traceId,
        String correlationId,
        BookingNotificationPayload payload) {
}
