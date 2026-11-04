package cl.campuslab.audit.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import cl.campuslab.audit.domain.TimelineEvent;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * audit-timeline-compacted.md §9 AC 10: mapeo campo a campo y eventId derivado determinista.
 */
class BookingStateSnapshotMapperTest {

    @Test
    void toEnvelope_mapsEveryFieldFromLatestTimelineEvent() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        String sourceEventId = UUID.randomUUID().toString();
        Instant occurredAt = Instant.parse("2026-11-04T15:02:10.987Z");
        Instant emittedAt = Instant.parse("2026-11-04T15:02:11.123Z");
        TimelineEvent latest = new TimelineEvent(sourceEventId, bookingId, resourceId, "BOOKING_APROBADA",
                "tecnico-oid", "TECNICO", "student-oid", "SOLICITADA", "APROBADA", "trace-1", "corr-1", occurredAt);

        // Act
        BookingStateSnapshotEnvelope envelope = BookingStateSnapshotMapper.toEnvelope(latest, emittedAt);

        // Assert
        assertThat(envelope.type()).isEqualTo("BOOKING_STATE_SNAPSHOT");
        assertThat(envelope.eventId()).isEqualTo(BookingStateSnapshotMapper.derivedEventId(sourceEventId));
        assertThat(envelope.timestamp()).isEqualTo(emittedAt);
        assertThat(envelope.traceId()).isEqualTo("trace-1");
        assertThat(envelope.correlationId()).isEqualTo("corr-1");
        BookingStateSnapshotPayload payload = envelope.payload();
        assertThat(payload.bookingId()).isEqualTo(bookingId);
        assertThat(payload.resourceId()).isEqualTo(resourceId);
        assertThat(payload.studentOid()).isEqualTo("student-oid");
        assertThat(payload.currentStatus()).isEqualTo("APROBADA");
        assertThat(payload.lastEventType()).isEqualTo("BOOKING_APROBADA");
        assertThat(payload.lastActorOid()).isEqualTo("tecnico-oid");
        assertThat(payload.lastTransitionAt()).isEqualTo(occurredAt);
        assertThat(payload.sourceEventId()).isEqualTo(sourceEventId);
    }

    @Test
    void derivedEventId_sameSource_returnsSameId() {
        // Arrange
        String sourceEventId = UUID.randomUUID().toString();

        // Act
        String first = BookingStateSnapshotMapper.derivedEventId(sourceEventId);
        String second = BookingStateSnapshotMapper.derivedEventId(sourceEventId);

        // Assert
        assertThat(first).isEqualTo(second).hasSize(36);
    }

    @Test
    void derivedEventId_differentSource_returnsDifferentIdNeverEqualToSource() {
        // Arrange
        String sourceA = UUID.randomUUID().toString();
        String sourceB = UUID.randomUUID().toString();

        // Act
        String derivedA = BookingStateSnapshotMapper.derivedEventId(sourceA);
        String derivedB = BookingStateSnapshotMapper.derivedEventId(sourceB);

        // Assert
        assertThat(derivedA).isNotEqualTo(derivedB);
        assertThat(derivedA).isNotEqualTo(sourceA);
        assertThat(derivedB).isNotEqualTo(sourceB);
    }
}
