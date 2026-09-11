package cl.campuslab.audit.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Small composable filters for {@code GET /api/audit/timeline}'s optional, combinable
 * query params (design doc §3/§7 A03) - all bind-parameter JPA Criteria, never
 * string-concatenated JPQL or native SQL, mirroring bookings' own {@code
 * BookingSpecifications}.
 */
public final class TimelineEventSpecifications {

    private TimelineEventSpecifications() {
    }

    /** {@code userOid} is an OR across two columns (design doc §3/§4's rationale) - a
     * técnico's own actions and a student's own bookings are both legitimate readings
     * of "filtro por usuario". */
    public static Specification<TimelineEvent> actorOrStudentOidEquals(String userOid) {
        return (root, query, cb) ->
                cb.or(cb.equal(root.get("actorOid"), userOid), cb.equal(root.get("studentOid"), userOid));
    }

    public static Specification<TimelineEvent> eventTypeEquals(String eventType) {
        return (root, query, cb) -> cb.equal(root.get("eventType"), eventType);
    }

    public static Specification<TimelineEvent> bookingIdEquals(UUID bookingId) {
        return (root, query, cb) -> cb.equal(root.get("bookingId"), bookingId);
    }

    public static Specification<TimelineEvent> occurredAtAfterOrEqual(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("occurredAt"), from);
    }

    public static Specification<TimelineEvent> occurredAtBeforeOrEqual(Instant to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("occurredAt"), to);
    }
}
