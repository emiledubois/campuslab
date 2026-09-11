package cl.campuslab.audit.web;

import cl.campuslab.audit.domain.TimelineEventRepository;
import cl.campuslab.audit.domain.TimelineEventSpecifications;
import cl.campuslab.audit.messaging.BookingStreamEventType;
import cl.campuslab.audit.web.dto.TimelineEventResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * All filter parsing/validation lives here, not the controller (mirrors bookings'
 * controller/service split) - {@code userOid} is an OR across {@code actorOid}/{@code
 * studentOid} (design doc §3/§4), every other filter combines with AND. {@code limit}
 * defaults to 200, silently clamped to 500 - never a 400 for "too large" (design doc
 * §3, the same "moves/returns what exists" convention as mq-admin's requeue).
 */
@Service
public class TimelineQueryService {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;

    private final TimelineEventRepository repository;

    public TimelineQueryService(TimelineEventRepository repository) {
        this.repository = repository;
    }

    public List<TimelineEventResponse> query(String userOid, String eventType, String bookingId, String from, String to, Integer limit) {
        Specification<cl.campuslab.audit.domain.TimelineEvent> spec = Specification.where(null);

        if (userOid != null && !userOid.isBlank()) {
            spec = spec.and(TimelineEventSpecifications.actorOrStudentOidEquals(userOid));
        }
        if (eventType != null) {
            spec = spec.and(TimelineEventSpecifications.eventTypeEquals(parseEventType(eventType)));
        }
        if (bookingId != null) {
            spec = spec.and(TimelineEventSpecifications.bookingIdEquals(parseUuid(bookingId, "bookingId")));
        }
        Instant parsedFrom = parseInstant(from, "from");
        Instant parsedTo = parseInstant(to, "to");
        if (parsedFrom != null && parsedTo != null && parsedFrom.isAfter(parsedTo)) {
            throw new InvalidTimelineQueryException("'from' must not be after 'to'");
        }
        if (parsedFrom != null) {
            spec = spec.and(TimelineEventSpecifications.occurredAtAfterOrEqual(parsedFrom));
        }
        if (parsedTo != null) {
            spec = spec.and(TimelineEventSpecifications.occurredAtBeforeOrEqual(parsedTo));
        }

        int clampedLimit = clampLimit(limit);
        PageRequest pageRequest = PageRequest.of(0, clampedLimit, Sort.by("occurredAt").descending());
        return repository.findAll(spec, pageRequest).getContent().stream().map(TimelineEventResponse::from).toList();
    }

    private static String parseEventType(String eventType) {
        if (!BookingStreamEventType.RECOGNIZED.contains(eventType)) {
            throw new InvalidTimelineQueryException("'eventType' is not a recognized value: " + eventType);
        }
        return eventType;
    }

    private static UUID parseUuid(String value, String paramName) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidTimelineQueryException("'" + paramName + "' is not a valid UUID");
        }
    }

    private static Instant parseInstant(String value, String paramName) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ex) {
            throw new InvalidTimelineQueryException("'" + paramName + "' is not a valid ISO-8601 instant");
        }
    }

    private static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }
}
