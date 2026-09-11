package cl.campuslab.report.messaging;

import java.util.List;
import java.util.UUID;

/**
 * Copied, not shared, from {@code ms-campuslab-bookings}' own payload shape (design
 * doc §5.2/§7 A08). {@code studentOid}/{@code actorOid}/{@code actorRoles} are present
 * on the wire but never persisted into {@link cl.campuslab.report.domain.ReportEvent}
 * (design doc §4's minimization) - report only ever reads {@code bookingId}/{@code
 * resourceId}/{@code fromStatus}/{@code toStatus} out of this record.
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
