package cl.campuslab.bookings.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import cl.campuslab.bookings.domain.Booking;
import cl.campuslab.bookings.domain.BookingStatus;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * design doc §2.1's fire-and-forget contract, in isolation: a publish failure must never
 * propagate to the caller, and the successful path must publish to the exact
 * exchange/routing key the message's {@link NotificationType} names.
 */
@ExtendWith(MockitoExtension.class)
class BookingEventPublisherTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private BookingEventPublisher publisher;
    private Booking booking;

    @BeforeEach
    void setUp() {
        BookingsRabbitProperties rabbitProperties = new BookingsRabbitProperties(
                "cmd.direct", new BookingsRabbitProperties.RoutingKey("email.send", "prep.ticket"));
        publisher = new BookingEventPublisher(rabbitTemplate, rabbitProperties);
        booking = new Booking(UUID.randomUUID(), "student-oid", Instant.now(), Instant.now().plusSeconds(3600), null);
        setField(booking, "id", UUID.randomUUID());
    }

    @Test
    void publish_success_sendsToCmdDirectWithTypesOwnRoutingKey() {
        publisher.publish(NotificationType.EMAIL_APPROVED, booking, BookingStatus.SOLICITADA, BookingStatus.APROBADA, "trace-1");

        verify(rabbitTemplate).convertAndSend(eq("cmd.direct"), eq("email.send"), any(NotificationEnvelope.class));
    }

    @Test
    void publish_prepTicket_usesPrepTicketRoutingKey() {
        publisher.publish(NotificationType.PREP_TICKET_REQUESTED, booking, BookingStatus.SOLICITADA, BookingStatus.APROBADA, "trace-1");

        verify(rabbitTemplate).convertAndSend(eq("cmd.direct"), eq("prep.ticket"), any(NotificationEnvelope.class));
    }

    @Test
    void publish_whenRabbitUnreachable_neverThrows() {
        willThrow(new AmqpConnectException(new RuntimeException("refused")))
                .given(rabbitTemplate).convertAndSend(any(String.class), any(String.class), any(Object.class));

        assertThatCode(() ->
                publisher.publish(NotificationType.EMAIL_APPROVED, booking, BookingStatus.SOLICITADA, BookingStatus.APROBADA, "trace-1"))
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
