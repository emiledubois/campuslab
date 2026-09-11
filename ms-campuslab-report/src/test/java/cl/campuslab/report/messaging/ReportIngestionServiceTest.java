package cl.campuslab.report.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cl.campuslab.report.domain.ReportEvent;
import cl.campuslab.report.domain.ReportEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * design doc §5.3's idempotency/classification contract, in isolation: a duplicate
 * eventId is a no-op (whether caught early via existsByEventId or late via the unique
 * constraint itself), an unrecognized type or malformed envelope never reaches the
 * repository, and a genuine DataAccessException propagates so the container's
 * DefaultErrorHandler can retry it.
 */
@ExtendWith(MockitoExtension.class)
class ReportIngestionServiceTest {

    @Mock
    private ReportEventRepository repository;

    private ReportIngestionService service;

    private BookingStreamEnvelope validEnvelope() {
        return new BookingStreamEnvelope(
                BookingStreamEventType.BOOKING_APROBADA, UUID.randomUUID().toString(), Instant.now(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                new BookingStreamPayload(
                        UUID.randomUUID(), UUID.randomUUID(), "student-oid", "tecnico-oid", List.of("TECNICO"),
                        "SOLICITADA", "APROBADA"));
    }

    @Test
    void ingest_freshEvent_persists() {
        service = new ReportIngestionService(repository);
        BookingStreamEnvelope envelope = validEnvelope();
        given(repository.existsByEventId(envelope.eventId())).willReturn(false);

        service.ingest(envelope);

        verify(repository).save(any(ReportEvent.class));
    }

    @Test
    void ingest_alreadyPersistedEventId_skipsWithoutSaving() {
        service = new ReportIngestionService(repository);
        BookingStreamEnvelope envelope = validEnvelope();
        given(repository.existsByEventId(envelope.eventId())).willReturn(true);

        service.ingest(envelope);

        verify(repository, never()).save(any());
    }

    @Test
    void ingest_uniqueConstraintRaceLostAtInsertTime_swallowsAsDuplicate() {
        service = new ReportIngestionService(repository);
        BookingStreamEnvelope envelope = validEnvelope();
        given(repository.existsByEventId(envelope.eventId())).willReturn(false);
        given(repository.save(any(ReportEvent.class)))
                .willThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatCode(() -> service.ingest(envelope)).doesNotThrowAnyException();
    }

    @Test
    void ingest_unrecognizedType_throwsAndNeverSaves() {
        service = new ReportIngestionService(repository);
        BookingStreamEnvelope envelope = new BookingStreamEnvelope(
                "NOT_A_REAL_TYPE", UUID.randomUUID().toString(), Instant.now(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                new BookingStreamPayload(
                        UUID.randomUUID(), UUID.randomUUID(), "student-oid", "tecnico-oid", List.of("TECNICO"),
                        "SOLICITADA", "APROBADA"));

        assertThatThrownBy(() -> service.ingest(envelope)).isInstanceOf(UnrecognizedEventTypeException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void ingest_envelopeMissingBookingId_throwsUnparseableAndNeverSaves() {
        service = new ReportIngestionService(repository);
        BookingStreamEnvelope envelope = new BookingStreamEnvelope(
                BookingStreamEventType.BOOKING_APROBADA, UUID.randomUUID().toString(), Instant.now(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                new BookingStreamPayload(
                        null, UUID.randomUUID(), "student-oid", "tecnico-oid", List.of("TECNICO"),
                        "SOLICITADA", "APROBADA"));

        assertThatThrownBy(() -> service.ingest(envelope)).isInstanceOf(UnparseableEnvelopeException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void ingest_nullEnvelope_throwsUnparseable() {
        service = new ReportIngestionService(repository);

        assertThatThrownBy(() -> service.ingest(null)).isInstanceOf(UnparseableEnvelopeException.class);
    }

    @Test
    void ingest_transientDbFailure_propagatesForContainerRetry() {
        service = new ReportIngestionService(repository);
        BookingStreamEnvelope envelope = validEnvelope();
        given(repository.existsByEventId(envelope.eventId())).willReturn(false);
        given(repository.save(any(ReportEvent.class)))
                .willThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() -> service.ingest(envelope)).isInstanceOf(DataAccessResourceFailureException.class);
    }
}
