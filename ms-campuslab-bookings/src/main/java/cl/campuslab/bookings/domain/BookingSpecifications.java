package cl.campuslab.bookings.domain;

import java.time.Instant;
import org.springframework.data.jpa.domain.Specification;

/**
 * Small composable filters for GET /api/bookings' optional, combinable query
 * params (status/from/to) plus the ESTUDIANTE ownership scope - all bind-parameter
 * JPA Criteria, never string-concatenated JPQL or native SQL (OWASP A03).
 */
public final class BookingSpecifications {

    private BookingSpecifications() {
    }

    public static Specification<Booking> hasStudentOid(String studentOid) {
        return (root, query, cb) -> cb.equal(root.get("studentOid"), studentOid);
    }

    public static Specification<Booking> hasStatus(BookingStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Booking> requestedStartAfterOrEqual(Instant from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("requestedStart"), from);
    }

    public static Specification<Booking> requestedStartBeforeOrEqual(Instant to) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("requestedStart"), to);
    }
}
