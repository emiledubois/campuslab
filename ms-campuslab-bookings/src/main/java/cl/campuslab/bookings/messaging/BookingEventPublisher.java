package cl.campuslab.bookings.messaging;

import cl.campuslab.bookings.domain.Booking;
import cl.campuslab.bookings.domain.BookingStatus;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Best-effort/fire-and-forget relative to the booking's own already-committed state
 * change (design doc §2.1) - always called strictly AFTER a successful {@code
 * saveAndFlush()}, never inside the same transaction (nothing to roll back to) and never
 * gating the HTTP response. A publish failure (RabbitMQ unreachable, exchange/queue
 * missing because mq-admin never ran, serialization failure) is caught here, logged
 * ERROR with full context - deliberately never the bearer token - and swallowed: the
 * caller of {@link #publish} never sees an exception, by design, for any of the four
 * message types.
 */
@Component
public class BookingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BookingEventPublisher.class);
    private static final String EXCHANGE_CMD_DIRECT = "cmd.direct";

    private final RabbitTemplate rabbitTemplate;

    public BookingEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void publish(NotificationType type, Booking booking, BookingStatus fromStatus, BookingStatus toStatus, String traceId) {
        String eventId = UUID.randomUUID().toString();
        BookingNotificationPayload payload = new BookingNotificationPayload(
                booking.getId(), booking.getResourceId(), booking.getStudentOid(), null, fromStatus, toStatus);
        NotificationEnvelope envelope = new NotificationEnvelope(
                type, eventId, Instant.now(), traceId, booking.getId().toString(), payload);

        try {
            rabbitTemplate.convertAndSend(EXCHANGE_CMD_DIRECT, type.routingKey(), envelope);
            log.info("Booking notification: outcome=[PUBLISHED] type=[{}] eventId=[{}] bookingId=[{}] resourceId=[{}]",
                    type, eventId, booking.getId(), booking.getResourceId());
        } catch (Exception ex) {
            // Deliberately broad: any publish-time failure (connection refused, missing
            // exchange, serialization error) must never propagate to the caller (design
            // doc §2.1) - it is always logged ERROR with full, non-sensitive context, never
            // swallowed silently and never downgraded to DEBUG.
            log.error("Booking notification: outcome=[PUBLISH_FAILED] type=[{}] eventId=[{}] bookingId=[{}] "
                            + "resourceId=[{}] exceptionClass=[{}] exceptionMessage=[{}]",
                    type, eventId, booking.getId(), booking.getResourceId(), ex.getClass().getName(), ex.getMessage());
        }
    }
}
