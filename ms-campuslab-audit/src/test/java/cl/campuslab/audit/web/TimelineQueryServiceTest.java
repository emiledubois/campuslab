package cl.campuslab.audit.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.campuslab.audit.AbstractIntegrationTest;
import cl.campuslab.audit.domain.TimelineEvent;
import cl.campuslab.audit.domain.TimelineEventRepository;
import cl.campuslab.audit.messaging.BookingStreamEventType;
import cl.campuslab.audit.web.dto.TimelineEventResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * design doc §9 AC9's filter behaviour, against a real Postgres (Testcontainers) so the
 * JPA Specification query is genuinely exercised, not assumed - a mocked repository
 * couldn't prove the OR-across-two-columns semantics of {@code userOid} (§3/§4).
 */
@SpringBootTest
class TimelineQueryServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TimelineQueryService queryService;

    @Autowired
    private TimelineEventRepository repository;

    // Business-logic test, not an auth test (proven independently elsewhere) - mocked
    // purely so the context doesn't try to reach a real Entra issuer.
    @MockBean
    private JwtDecoder jwtDecoder;

    private UUID bookingId;
    private String studentOid;
    private String technicianOid;
    private Instant approvedAt;

    @BeforeEach
    void seedTimeline() {
        repository.deleteAll();
        bookingId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        studentOid = "student-" + UUID.randomUUID();
        technicianOid = "tecnico-" + UUID.randomUUID();
        Instant base = Instant.now().minus(1, ChronoUnit.HOURS);
        approvedAt = base.plusSeconds(60);

        save(bookingId, resourceId, BookingStreamEventType.BOOKING_SOLICITADA, studentOid, studentOid, null, "SOLICITADA", base);
        save(bookingId, resourceId, BookingStreamEventType.BOOKING_APROBADA, technicianOid, studentOid, "SOLICITADA", "APROBADA", approvedAt);
        save(bookingId, resourceId, BookingStreamEventType.BOOKING_EN_PREPARACION, technicianOid, studentOid, "APROBADA", "EN_PREPARACION", base.plusSeconds(120));
        save(bookingId, resourceId, BookingStreamEventType.BOOKING_EN_USO, technicianOid, studentOid, "EN_PREPARACION", "EN_USO", base.plusSeconds(180));
        save(bookingId, resourceId, BookingStreamEventType.BOOKING_DEVUELTA, technicianOid, studentOid, "EN_USO", "DEVUELTA", base.plusSeconds(240));
    }

    private void save(UUID booking, UUID resource, String type, String actorOid, String student, String from, String to, Instant occurredAt) {
        repository.save(new TimelineEvent(
                UUID.randomUUID().toString(), booking, resource, type, actorOid, "TECNICO", student, from, to,
                UUID.randomUUID().toString(), booking.toString(), occurredAt));
    }

    @Test
    void query_byEventType_returnsOnlyMatchingRow() {
        List<TimelineEventResponse> results = queryService.query(null, BookingStreamEventType.BOOKING_APROBADA, null, null, null, null);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).eventType()).isEqualTo(BookingStreamEventType.BOOKING_APROBADA);
    }

    @Test
    void query_byTechnicianUserOid_returnsRowsWhereTechnicianIsActor() {
        List<TimelineEventResponse> results = queryService.query(technicianOid, null, null, null, null, null);

        assertThat(results).hasSize(4);
        assertThat(results).allSatisfy(r -> assertThat(r.actorOid()).isEqualTo(technicianOid));
    }

    @Test
    void query_byStudentUserOid_returnsAllRowsForThatBooking() {
        List<TimelineEventResponse> results = queryService.query(studentOid, null, null, null, null, null);

        assertThat(results).hasSize(5);
    }

    @Test
    void query_dateRangeAfterAllEvents_returnsEmpty() {
        List<TimelineEventResponse> results = queryService.query(
                null, null, null, approvedAt.plusSeconds(3600).toString(), Instant.now().plusSeconds(7200).toString(), null);

        assertThat(results).isEmpty();
    }

    @Test
    void query_orderedByOccurredAtDescending() {
        List<TimelineEventResponse> results = queryService.query(null, null, bookingId.toString(), null, null, null);

        assertThat(results).hasSize(5);
        assertThat(results).isSortedAccordingTo((a, b) -> b.occurredAt().compareTo(a.occurredAt()));
    }

    @Test
    void query_unrecognizedEventType_throws() {
        assertThatThrownBy(() -> queryService.query(null, "NOT_A_REAL_TYPE", null, null, null, null))
                .isInstanceOf(InvalidTimelineQueryException.class);
    }

    @Test
    void query_malformedBookingId_throws() {
        assertThatThrownBy(() -> queryService.query(null, null, "not-a-uuid", null, null, null))
                .isInstanceOf(InvalidTimelineQueryException.class);
    }

    @Test
    void query_fromAfterTo_throws() {
        assertThatThrownBy(() -> queryService.query(
                        null, null, null, "2026-01-01T00:00:00Z", "2025-01-01T00:00:00Z", null))
                .isInstanceOf(InvalidTimelineQueryException.class);
    }

    @Test
    void query_limitAboveMax_clampsToFiveHundredNeverThrows() {
        List<TimelineEventResponse> results = queryService.query(null, null, null, null, null, 10_000);

        assertThat(results).hasSizeLessThanOrEqualTo(500);
    }
}
