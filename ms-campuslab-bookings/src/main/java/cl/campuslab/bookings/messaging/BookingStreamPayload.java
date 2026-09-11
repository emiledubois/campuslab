package cl.campuslab.bookings.messaging;

import cl.campuslab.bookings.domain.BookingStatus;
import java.util.List;
import java.util.UUID;

/**
 * {@code actorOid}/{@code actorRoles} are new relative to {@link
 * BookingNotificationPayload} (design doc §5.2) - who performed THIS transition (the
 * caller), never a generic recipient concept. {@code studentOid} is retained separately
 * as the booking's owner/subject: for staff-performed transitions the two differ, and
 * that distinction is exactly what answers "quien aprobo/entrego/recibio" (§1).
 */
public record BookingStreamPayload(
        UUID bookingId,
        UUID resourceId,
        String studentOid,
        String actorOid,
        List<String> actorRoles,
        BookingStatus fromStatus,
        BookingStatus toStatus) {
}
