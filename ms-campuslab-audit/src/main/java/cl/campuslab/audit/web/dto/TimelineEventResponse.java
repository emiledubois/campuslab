package cl.campuslab.audit.web.dto;

import cl.campuslab.audit.domain.TimelineEvent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Response shape for {@code GET /api/audit/timeline} (design doc §3) - {@code
 * correlationId} is deliberately omitted (not part of the documented response shape).
 */
public record TimelineEventResponse(
        UUID id,
        String eventId,
        UUID bookingId,
        UUID resourceId,
        String eventType,
        String actorOid,
        List<String> actorRoles,
        String studentOid,
        String fromStatus,
        String toStatus,
        String traceId,
        Instant occurredAt,
        Instant receivedAt) {

    public static TimelineEventResponse from(TimelineEvent event) {
        return new TimelineEventResponse(
                event.getId(),
                event.getEventId(),
                event.getBookingId(),
                event.getResourceId(),
                event.getEventType(),
                event.getActorOid(),
                splitRoles(event.getActorRoles()),
                event.getStudentOid(),
                event.getFromStatus(),
                event.getToStatus(),
                event.getTraceId(),
                event.getOccurredAt(),
                event.getReceivedAt());
    }

    private static List<String> splitRoles(String actorRoles) {
        return actorRoles == null || actorRoles.isBlank() ? List.of() : Arrays.asList(actorRoles.split(","));
    }
}
