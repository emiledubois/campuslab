package cl.campuslab.report.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Map;
import java.util.UUID;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ConsumerSeekAware.ConsumerSeekCallback;

/**
 * audit-timeline-compacted.md §9 AC 12: regla de reconstruccion en {@code onPartitionsAssigned}.
 */
@ExtendWith(MockitoExtension.class)
class TimelineSnapshotListenerTest {

    @Mock
    private BookingStateProjectionService projectionService;

    @Mock
    private ConsumerSeekCallback callback;

    private TimelineSnapshotListener listener;

    private final Map<TopicPartition, Long> assignments = Map.of(
            new TopicPartition("audit.timeline", 0), 10L,
            new TopicPartition("audit.timeline", 1), 4L,
            new TopicPartition("audit.timeline", 2), 7L);

    @BeforeEach
    void setUp() {
        listener = new TimelineSnapshotListener(projectionService);
    }

    @Test
    void onPartitionsAssigned_emptyProjection_seeksToBeginning() {
        // Arrange
        given(projectionService.isEmpty()).willReturn(true);

        // Act
        listener.onPartitionsAssigned(assignments, callback);

        // Assert
        verify(callback).seekToBeginning(assignments.keySet());
    }

    @Test
    void onPartitionsAssigned_projectionWithRows_doesNotSeek() {
        // Arrange
        given(projectionService.isEmpty()).willReturn(false);

        // Act
        listener.onPartitionsAssigned(assignments, callback);

        // Assert
        verifyNoInteractions(callback);
    }

    @Test
    void onPartitionsAssigned_queryFails_doesNotSeekAndDoesNotThrow() {
        // Arrange
        given(projectionService.isEmpty()).willThrow(new DataAccessResourceFailureException("db down"));

        // Act / Assert
        assertThatCode(() -> listener.onPartitionsAssigned(assignments, callback)).doesNotThrowAnyException();
        verifyNoInteractions(callback);
    }

    @Test
    void onSnapshot_delegatesKeyAndEnvelopeToService() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        BookingStateSnapshotEnvelope envelope = new BookingStateSnapshotEnvelope(BookingStateSnapshotEnvelope.TYPE,
                "e-1", java.time.Instant.now(), "t", bookingId.toString(),
                new BookingStateSnapshotPayload(bookingId, UUID.randomUUID(), "APROBADA", java.time.Instant.now()));

        // Act
        listener.onSnapshot(envelope, bookingId.toString());

        // Assert
        verify(projectionService).apply(bookingId.toString(), envelope);
    }
}
