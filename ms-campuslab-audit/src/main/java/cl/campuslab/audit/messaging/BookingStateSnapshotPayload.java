package cl.campuslab.audit.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Campos justificados uno a uno en audit-timeline-compacted.md §5.3.1. {@code actorRoles} y
 * {@code fromStatus} se omiten a proposito.
 */
public record BookingStateSnapshotPayload(
        UUID bookingId,
        UUID resourceId,
        String studentOid,
        String currentStatus,
        String lastEventType,
        String lastActorOid,
        Instant lastTransitionAt,
        String sourceEventId) {
}
