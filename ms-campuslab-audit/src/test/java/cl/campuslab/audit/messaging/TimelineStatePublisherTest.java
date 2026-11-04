package cl.campuslab.audit.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import cl.campuslab.audit.domain.TimelineEvent;
import cl.campuslab.audit.domain.TimelineEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * audit-timeline-compacted.md §9 AC 10: topico, key, estado leido de la base y clasificacion
 * de fallos del publicador.
 */
@ExtendWith(MockitoExtension.class)
class TimelineStatePublisherTest {

    private static final Instant NOW = Instant.parse("2026-11-04T15:00:00Z");

    @Mock
    private TimelineEventRepository repository;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private TimelineStatePublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new TimelineStatePublisher(
                new TimelineStateReader(repository), kafkaTemplate, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishCurrentState_sendsToAuditTimelineKeyedByBookingId() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        TimelineEvent latest = event(bookingId, "BOOKING_APROBADA", "APROBADA", Instant.parse("2026-11-04T14:00:00Z"));
        given(repository.findFirstByBookingIdOrderByOccurredAtDescReceivedAtDesc(bookingId)).willReturn(Optional.of(latest));
        given(kafkaTemplate.send(anyString(), anyString(), any())).willReturn(CompletableFuture.completedFuture(sendResult()));

        // Act
        publisher.publishCurrentState(bookingId);

        // Assert
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("audit.timeline"), eq(bookingId.toString()), value.capture());
        BookingStateSnapshotEnvelope snapshot = (BookingStateSnapshotEnvelope) value.getValue();
        assertThat(snapshot.type()).isEqualTo("BOOKING_STATE_SNAPSHOT");
        assertThat(snapshot.timestamp()).isEqualTo(NOW);
        assertThat(snapshot.payload().bookingId()).isEqualTo(bookingId);
        assertThat(snapshot.payload().currentStatus()).isEqualTo("APROBADA");
    }

    @Test
    void publishCurrentState_publishesLatestPersistedRowNotTheIncomingFact() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        TimelineEvent t2 = event(bookingId, "BOOKING_EN_PREPARACION", "EN_PREPARACION", Instant.parse("2026-11-04T14:10:00Z"));
        given(repository.findFirstByBookingIdOrderByOccurredAtDescReceivedAtDesc(bookingId)).willReturn(Optional.of(t2));
        given(kafkaTemplate.send(anyString(), anyString(), any())).willReturn(CompletableFuture.completedFuture(sendResult()));

        // Act
        publisher.publishCurrentState(bookingId);

        // Assert
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq("audit.timeline"), eq(bookingId.toString()), value.capture());
        BookingStateSnapshotEnvelope snapshot = (BookingStateSnapshotEnvelope) value.getValue();
        assertThat(snapshot.payload().currentStatus()).isEqualTo("EN_PREPARACION");
        assertThat(snapshot.payload().sourceEventId()).isEqualTo(t2.getEventId());
        assertThat(snapshot.eventId()).isEqualTo(BookingStateSnapshotMapper.derivedEventId(t2.getEventId()));
    }

    @Test
    void publishCurrentState_failedFuture_throwsTimelinePublishException() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        given(repository.findFirstByBookingIdOrderByOccurredAtDescReceivedAtDesc(bookingId))
                .willReturn(Optional.of(event(bookingId, "BOOKING_APROBADA", "APROBADA", NOW)));
        given(kafkaTemplate.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        // Act / Assert
        assertThatThrownBy(() -> publisher.publishCurrentState(bookingId)).isInstanceOf(TimelinePublishException.class);
    }

    @Test
    void publishCurrentState_timeout_throwsTimelinePublishException() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        given(repository.findFirstByBookingIdOrderByOccurredAtDescReceivedAtDesc(bookingId))
                .willReturn(Optional.of(event(bookingId, "BOOKING_APROBADA", "APROBADA", NOW)));
        CompletableFuture<SendResult<String, Object>> timedOut = new CompletableFuture<>();
        timedOut.completeExceptionally(new TimeoutException("timeout"));
        given(kafkaTemplate.send(anyString(), anyString(), any())).willReturn(timedOut);

        // Act / Assert
        assertThatThrownBy(() -> publisher.publishCurrentState(bookingId))
                .isInstanceOf(TimelinePublishException.class)
                .hasRootCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void publishCurrentState_sendThrowsSynchronously_throwsTimelinePublishException() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        given(repository.findFirstByBookingIdOrderByOccurredAtDescReceivedAtDesc(bookingId))
                .willReturn(Optional.of(event(bookingId, "BOOKING_APROBADA", "APROBADA", NOW)));
        given(kafkaTemplate.send(anyString(), anyString(), any()))
                .willThrow(new org.apache.kafka.common.errors.TimeoutException("max.block.ms"));

        // Act / Assert
        assertThatThrownBy(() -> publisher.publishCurrentState(bookingId)).isInstanceOf(TimelinePublishException.class);
    }

    @Test
    void publishCurrentState_noPersistedRow_neitherSendsNorThrows() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        given(repository.findFirstByBookingIdOrderByOccurredAtDescReceivedAtDesc(bookingId)).willReturn(Optional.empty());

        // Act / Assert
        assertThatCode(() -> publisher.publishCurrentState(bookingId)).doesNotThrowAnyException();
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }

    private static TimelineEvent event(UUID bookingId, String type, String toStatus, Instant occurredAt) {
        return new TimelineEvent(UUID.randomUUID().toString(), bookingId, UUID.randomUUID(), type, "actor-oid",
                "TECNICO", "student-oid", null, toStatus, "trace", bookingId.toString(), occurredAt);
    }

    private static SendResult<String, Object> sendResult() {
        RecordMetadata metadata = new RecordMetadata(new TopicPartition("audit.timeline", 1), 7L, 0, 0L, 0, 0);
        return new SendResult<>(new ProducerRecord<>("audit.timeline", "k", "v"), metadata);
    }
}
