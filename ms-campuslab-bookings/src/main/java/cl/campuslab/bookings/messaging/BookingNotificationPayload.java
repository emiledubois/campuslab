package cl.campuslab.bookings.messaging;

import cl.campuslab.bookings.domain.BookingStatus;
import java.util.UUID;

/**
 * Deliberately no name/email anywhere (design doc §5.2/§7 A02) - only the pseudonymous
 * Entra {@code oid} already used as bookings' own ownership key. {@code technicianOid}
 * is always {@code null} in this slice: the {@code Booking} entity has no "assigned
 * technician" concept, so the prep ticket's recipient is "a technician", generically, not
 * a specific one - notify's own logging reflects that (design doc §5.4).
 */
public record BookingNotificationPayload(
        UUID bookingId,
        UUID resourceId,
        String studentOid,
        String technicianOid,
        BookingStatus fromStatus,
        BookingStatus toStatus) {
}
