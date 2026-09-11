package cl.campuslab.audit.messaging;

import java.util.List;
import java.util.UUID;

/**
 * Copied, not shared, from {@code ms-campuslab-bookings}' own payload shape (design
 * doc §5.4/§7 A08 - audit's first trust boundary, a typed DTO, never a dynamic
 * structure). {@code fromStatus}/{@code toStatus} are plain strings here (not the
 * {@code BookingStatus} enum) - audit only ever stores and displays them, never
 * enforces the state machine.
 */
public record BookingStreamPayload(
        UUID bookingId,
        UUID resourceId,
        String studentOid,
        String actorOid,
        List<String> actorRoles,
        String fromStatus,
        String toStatus) {
}
