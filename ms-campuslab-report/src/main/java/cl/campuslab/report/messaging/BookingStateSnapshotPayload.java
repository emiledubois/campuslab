package cl.campuslab.report.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * Solo los campos que report usa. {@code studentOid}, {@code lastActorOid},
 * {@code lastEventType} y {@code sourceEventId} se ignoran al deserializar: report nunca
 * toca identidades (audit-timeline-compacted.md §5.4.1).
 */
public record BookingStateSnapshotPayload(
        UUID bookingId,
        UUID resourceId,
        String currentStatus,
        Instant lastTransitionAt) {
}
