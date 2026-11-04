package cl.campuslab.audit.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * audit-timeline-compacted.md §9 AC 10: primero ingest (commit) y despues publish; un fallo
 * de publish se propaga para que el container reintente.
 */
@ExtendWith(MockitoExtension.class)
class BookingEventListenerTest {

    @Mock
    private TimelineIngestionService ingestionService;

    @Mock
    private TimelineStatePublisher timelineStatePublisher;

    private BookingEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new BookingEventListener(ingestionService, timelineStatePublisher);
    }

    @Test
    void onBookingEvent_ingestsThenPublishesCurrentState() {
        // Arrange
        BookingStreamEnvelope envelope = envelope();

        // Act
        listener.onBookingEvent(envelope);

        // Assert
        InOrder order = inOrder(ingestionService, timelineStatePublisher);
        order.verify(ingestionService).ingest(envelope);
        order.verify(timelineStatePublisher).publishCurrentState(envelope.payload().bookingId());
    }

    @Test
    void onBookingEvent_ingestThrows_neverPublishes() {
        // Arrange
        BookingStreamEnvelope envelope = envelope();
        willThrow(new DataAccessResourceFailureException("db down")).given(ingestionService).ingest(envelope);

        // Act / Assert
        assertThatThrownBy(() -> listener.onBookingEvent(envelope)).isInstanceOf(DataAccessResourceFailureException.class);
        verify(timelineStatePublisher, never()).publishCurrentState(any());
    }

    @Test
    void onBookingEvent_publishThrows_propagatesForContainerRetry() {
        // Arrange
        BookingStreamEnvelope envelope = envelope();
        UUID bookingId = envelope.payload().bookingId();
        willThrow(new TimelinePublishException(bookingId.toString(), new RuntimeException("broker down")))
                .given(timelineStatePublisher).publishCurrentState(bookingId);

        // Act / Assert
        assertThatThrownBy(() -> listener.onBookingEvent(envelope)).isInstanceOf(TimelinePublishException.class);
        verify(ingestionService).ingest(envelope);
    }

    private static BookingStreamEnvelope envelope() {
        UUID bookingId = UUID.randomUUID();
        return new BookingStreamEnvelope(
                BookingStreamEventType.BOOKING_APROBADA, UUID.randomUUID().toString(), Instant.now(),
                UUID.randomUUID().toString(), bookingId.toString(),
                new BookingStreamPayload(bookingId, UUID.randomUUID(), "student-oid", "tecnico-oid",
                        List.of("TECNICO"), "SOLICITADA", "APROBADA"));
    }
}
