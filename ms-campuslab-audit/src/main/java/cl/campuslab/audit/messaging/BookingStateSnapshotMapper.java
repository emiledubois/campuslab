package cl.campuslab.audit.messaging;

import cl.campuslab.audit.domain.TimelineEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Mapper puro (audit-timeline-compacted.md §5.3.2): sin Spring y sin I/O.
 */
public final class BookingStateSnapshotMapper {

    private static final String DERIVED_ID_PREFIX = "audit.timeline:";

    private BookingStateSnapshotMapper() {
    }

    public static BookingStateSnapshotEnvelope toEnvelope(TimelineEvent latest, Instant emittedAt) {
        BookingStateSnapshotPayload payload = new BookingStateSnapshotPayload(
                latest.getBookingId(),
                latest.getResourceId(),
                latest.getStudentOid(),
                latest.getToStatus(),
                latest.getEventType(),
                latest.getActorOid(),
                latest.getOccurredAt(),
                latest.getEventId());
        return new BookingStateSnapshotEnvelope(
                BookingStateSnapshotEnvelope.TYPE,
                derivedEventId(latest.getEventId()),
                emittedAt,
                latest.getTraceId(),
                latest.getCorrelationId(),
                payload);
    }

    /** Determinista: el mismo estado republicado lleva el mismo eventId, y el consumidor lo deduplica. */
    public static String derivedEventId(String sourceEventId) {
        return UUID.nameUUIDFromBytes((DERIVED_ID_PREFIX + sourceEventId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
