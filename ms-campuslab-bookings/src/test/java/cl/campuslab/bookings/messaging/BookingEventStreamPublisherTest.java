package cl.campuslab.bookings.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import cl.campuslab.bookings.domain.Booking;
import cl.campuslab.bookings.domain.BookingStatus;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * design doc §2.2/§5.3's contract, in isolation: a publish failure must never propagate
 * to the caller, the message key must be the bookingId (per-booking ordering), and the
 * topic must always be {@code bookings.events}.
 */
@ExtendWith(MockitoExtension.class)
class BookingEventStreamPublisherTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private BookingEventStreamPublisher publisher;
    private Booking booking;

    @BeforeEach
    void setUp() {
        publisher = new BookingEventStreamPublisher(kafkaTemplate);
        booking = new Booking(UUID.randomUUID(), "student-oid", Instant.now(), Instant.now().plusSeconds(3600), null);
        setField(booking, "id", UUID.randomUUID());
    }

    @Test
    void publish_success_sendsToBookingsEventsKeyedByBookingId() {
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.complete(null);
        given(kafkaTemplate.send(eq("bookings.events"), eq(booking.getId().toString()), any())).willReturn(future);

        publisher.publish(
                BookingStreamEventType.BOOKING_APROBADA, booking, BookingStatus.SOLICITADA, BookingStatus.APROBADA,
                "trace-1", "tecnico-oid", List.of("TECNICO"));

        verify(kafkaTemplate).send(eq("bookings.events"), eq(booking.getId().toString()), any(BookingStreamEnvelope.class));
    }

    @Test
    void publish_whenKafkaUnreachable_neverThrows() {
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        future.completeExceptionally(new KafkaException("timed out"));
        given(kafkaTemplate.send(any(String.class), any(String.class), any())).willReturn(future);

        assertThatCode(() -> publisher.publish(
                BookingStreamEventType.BOOKING_SOLICITADA, booking, null, BookingStatus.SOLICITADA,
                "trace-1", "student-oid", List.of("ESTUDIANTE")))
                .doesNotThrowAnyException();
    }

    @Test
    void publish_whenSendThrowsSynchronously_neverThrows() {
        given(kafkaTemplate.send(any(String.class), any(String.class), any()))
                .willThrow(new KafkaException("producer closed"));

        assertThatCode(() -> publisher.publish(
                BookingStreamEventType.BOOKING_CANCELADA, booking, BookingStatus.SOLICITADA, BookingStatus.CANCELADA,
                "trace-1", "student-oid", List.of("ESTUDIANTE")))
                .doesNotThrowAnyException();
    }

    private static void setField(Booking booking, String fieldName, Object value) {
        try {
            Field field = Booking.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(booking, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
