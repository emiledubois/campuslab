package cl.campuslab.bookings.messaging;

import cl.campuslab.bookings.domain.Booking;
import cl.campuslab.bookings.domain.BookingStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Second, independent publish hook (design doc §1/§2's "Producer/Consumer over the
 * shared envelope convention") - deliberately NOT merged with {@link
 * BookingEventPublisher}: RabbitMQ notifications and the Kafka event stream are two
 * different concerns with two different scopes (design doc §2's "second producer,
 * first Kafka producer" framing). Order of operations, precisely (design doc §2.2): the
 * local state transition is always already committed by the time {@link #publish} is
 * called; a publish failure (timeout, Kafka unreachable, missing topic because
 * kafka-admin never ran) is caught here, logged ERROR, and never affects the caller's
 * HTTP response or the already-committed booking state.
 */
@Component
public class BookingEventStreamPublisher {

    private static final Logger log = LoggerFactory.getLogger(BookingEventStreamPublisher.class);
    private static final String TOPIC_BOOKINGS_EVENTS = "bookings.events";
    private static final long PUBLISH_WAIT_SECONDS = 3;

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public BookingEventStreamPublisher(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(
            BookingStreamEventType type, Booking booking, BookingStatus fromStatus, BookingStatus toStatus,
            String traceId, String actorOid, List<String> actorRoles) {
        String eventId = UUID.randomUUID().toString();
        BookingStreamPayload payload = new BookingStreamPayload(
                booking.getId(), booking.getResourceId(), booking.getStudentOid(), actorOid, actorRoles, fromStatus, toStatus);
        BookingStreamEnvelope envelope = new BookingStreamEnvelope(
                type, eventId, java.time.Instant.now(), traceId, booking.getId().toString(), payload);

        try {
            kafkaTemplate.send(TOPIC_BOOKINGS_EVENTS, booking.getId().toString(), envelope)
                    .get(PUBLISH_WAIT_SECONDS, TimeUnit.SECONDS);
            log.info("Booking event stream: outcome=[PUBLISHED] type=[{}] eventId=[{}] bookingId=[{}] resourceId=[{}]",
                    type, eventId, booking.getId(), booking.getResourceId());
        } catch (Exception ex) {
            // Deliberately broad, same posture as BookingEventPublisher (design doc §5.3):
            // any publish-time failure (timeout, connection refused, missing topic,
            // serialization error) must never propagate to the caller - never the bearer
            // token, always full non-sensitive context.
            log.error("Booking event stream: outcome=[PUBLISH_FAILED] type=[{}] eventId=[{}] bookingId=[{}] "
                            + "resourceId=[{}] exceptionClass=[{}] exceptionMessage=[{}]",
                    type, eventId, booking.getId(), booking.getResourceId(), ex.getClass().getName(), ex.getMessage());
        }
    }
}
