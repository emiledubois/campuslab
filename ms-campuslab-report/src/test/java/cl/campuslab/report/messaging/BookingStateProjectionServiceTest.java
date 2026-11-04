package cl.campuslab.report.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cl.campuslab.report.domain.BookingStateProjectionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * audit-timeline-compacted.md §9 AC 12: aplicacion, idempotencia, eventos atrasados y
 * clasificacion de poison de la proyeccion, en aislamiento.
 */
@ExtendWith(MockitoExtension.class)
class BookingStateProjectionServiceTest {

    @Mock
    private BookingStateProjectionRepository repository;

    private BookingStateProjectionService service;

    @BeforeEach
    void setUp() {
        service = new BookingStateProjectionService(repository);
    }

    @Test
    void apply_newSnapshot_upserts() {
        // Arrange
        BookingStateSnapshotEnvelope envelope = snapshot("APROBADA");
        BookingStateSnapshotPayload payload = envelope.payload();
        given(repository.existsByLastEventId(envelope.eventId())).willReturn(false);
        given(repository.upsertIfNewer(payload.bookingId(), payload.resourceId(), "APROBADA",
                payload.lastTransitionAt(), envelope.eventId())).willReturn(1);

        // Act
        service.apply(payload.bookingId().toString(), envelope);

        // Assert
        verify(repository).upsertIfNewer(payload.bookingId(), payload.resourceId(), "APROBADA",
                payload.lastTransitionAt(), envelope.eventId());
    }

    @Test
    void apply_duplicateEventId_skipsWithoutUpsert() {
        // Arrange
        BookingStateSnapshotEnvelope envelope = snapshot("APROBADA");
        given(repository.existsByLastEventId(envelope.eventId())).willReturn(true);

        // Act
        service.apply(envelope.payload().bookingId().toString(), envelope);

        // Assert
        verify(repository, never()).upsertIfNewer(any(), any(), any(), any(), any());
    }

    @Test
    void apply_staleSnapshot_upsertReturnsZeroAndDoesNotThrow() {
        // Arrange
        BookingStateSnapshotEnvelope envelope = snapshot("SOLICITADA");
        given(repository.existsByLastEventId(envelope.eventId())).willReturn(false);
        given(repository.upsertIfNewer(any(), any(), anyString(), any(), anyString())).willReturn(0);

        // Act / Assert
        assertThatCode(() -> service.apply(envelope.payload().bookingId().toString(), envelope))
                .doesNotThrowAnyException();
    }

    @Test
    void apply_uniqueConstraintRaceOnLastEventId_treatedAsDuplicate() {
        // Arrange
        BookingStateSnapshotEnvelope envelope = snapshot("APROBADA");
        given(repository.existsByLastEventId(envelope.eventId())).willReturn(false);
        given(repository.upsertIfNewer(any(), any(), anyString(), any(), anyString()))
                .willThrow(new DataIntegrityViolationException("uq_booking_state_projection_last_event_id"));

        // Act / Assert
        assertThatCode(() -> service.apply(envelope.payload().bookingId().toString(), envelope))
                .doesNotThrowAnyException();
    }

    @Test
    void apply_foreignType_throwsUnrecognizedEventType() {
        // Arrange
        BookingStateSnapshotEnvelope valid = snapshot("APROBADA");
        BookingStateSnapshotEnvelope envelope = new BookingStateSnapshotEnvelope("BOOKING_APROBADA", valid.eventId(),
                valid.timestamp(), valid.traceId(), valid.correlationId(), valid.payload());

        // Act / Assert
        assertThatThrownBy(() -> service.apply(valid.payload().bookingId().toString(), envelope))
                .isInstanceOf(UnrecognizedEventTypeException.class)
                .hasMessageContaining("audit.timeline");
        verify(repository, never()).existsByLastEventId(anyString());
    }

    @Test
    void apply_missingRequiredField_throwsUnparseable() {
        // Arrange
        BookingStateSnapshotEnvelope valid = snapshot("APROBADA");
        BookingStateSnapshotPayload payload = new BookingStateSnapshotPayload(
                valid.payload().bookingId(), null, "APROBADA", valid.payload().lastTransitionAt());
        BookingStateSnapshotEnvelope envelope = new BookingStateSnapshotEnvelope(valid.type(), valid.eventId(),
                valid.timestamp(), valid.traceId(), valid.correlationId(), payload);

        // Act / Assert
        assertThatThrownBy(() -> service.apply(valid.payload().bookingId().toString(), envelope))
                .isInstanceOf(UnparseableEnvelopeException.class)
                .hasMessageContaining("audit.timeline");
    }

    @Test
    void apply_nullEnvelopeTombstone_throwsUnparseable() {
        // Arrange
        String key = UUID.randomUUID().toString();

        // Act / Assert
        assertThatThrownBy(() -> service.apply(key, null)).isInstanceOf(UnparseableEnvelopeException.class);
        verify(repository, never()).upsertIfNewer(any(), any(), any(), any(), any());
    }

    @Test
    void apply_invalidStatus_throwsUnparseable() {
        // Arrange
        BookingStateSnapshotEnvelope envelope = snapshot("PERDIDA");

        // Act / Assert
        assertThatThrownBy(() -> service.apply(envelope.payload().bookingId().toString(), envelope))
                .isInstanceOf(UnparseableEnvelopeException.class);
        verify(repository, never()).upsertIfNewer(any(), any(), any(), any(), any());
    }

    @Test
    void apply_keyDifferentFromBookingId_throwsUnparseable() {
        // Arrange
        BookingStateSnapshotEnvelope envelope = snapshot("APROBADA");
        String otherKey = UUID.randomUUID().toString();

        // Act / Assert
        assertThatThrownBy(() -> service.apply(otherKey, envelope)).isInstanceOf(UnparseableEnvelopeException.class);
        verify(repository, never()).upsertIfNewer(any(), any(), any(), any(), any());
    }

    @Test
    void apply_nullKey_throwsUnparseable() {
        // Arrange
        BookingStateSnapshotEnvelope envelope = snapshot("APROBADA");

        // Act / Assert
        assertThatThrownBy(() -> service.apply(null, envelope)).isInstanceOf(UnparseableEnvelopeException.class);
    }

    @Test
    void apply_transientDbFailure_propagates() {
        // Arrange
        BookingStateSnapshotEnvelope envelope = snapshot("APROBADA");
        given(repository.existsByLastEventId(envelope.eventId()))
                .willThrow(new DataAccessResourceFailureException("connection refused"));

        // Act / Assert
        assertThatThrownBy(() -> service.apply(envelope.payload().bookingId().toString(), envelope))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void isEmpty_reflectsRowCount() {
        // Arrange
        given(repository.count()).willReturn(0L, 3L);

        // Act
        boolean first = service.isEmpty();
        boolean second = service.isEmpty();

        // Assert
        assertThat(first).isTrue();
        assertThat(second).isFalse();
    }

    private static BookingStateSnapshotEnvelope snapshot(String status) {
        UUID bookingId = UUID.randomUUID();
        return new BookingStateSnapshotEnvelope(BookingStateSnapshotEnvelope.TYPE, UUID.randomUUID().toString(),
                Instant.now(), UUID.randomUUID().toString(), bookingId.toString(),
                new BookingStateSnapshotPayload(bookingId, UUID.randomUUID(), status, Instant.now()));
    }
}
