package cl.campuslab.audit.messaging;

import cl.campuslab.audit.domain.TimelineEvent;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Publica el estado actual de una reserva en {@code audit.timeline}, leido de la base y no
 * del mensaje entrante (audit-timeline-compacted.md §5.3.3): asi un replay de un hecho
 * viejo nunca deja un estado falso como ultimo valor de la key compactada.
 */
@Component
public class TimelineStatePublisher {

    private static final Logger log = LoggerFactory.getLogger(TimelineStatePublisher.class);
    static final String TOPIC_AUDIT_TIMELINE = "audit.timeline";
    private static final long PUBLISH_WAIT_SECONDS = 3;

    private final TimelineStateReader stateReader;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Clock clock;

    @Autowired
    public TimelineStatePublisher(
            TimelineStateReader stateReader,
            @Qualifier("timelineKafkaTemplate") KafkaTemplate<String, Object> kafkaTemplate) {
        this(stateReader, kafkaTemplate, Clock.systemUTC());
    }

    TimelineStatePublisher(TimelineStateReader stateReader, KafkaTemplate<String, Object> kafkaTemplate, Clock clock) {
        this.stateReader = stateReader;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
    }

    public void publishCurrentState(UUID bookingId) {
        Optional<TimelineEvent> latest = stateReader.latestFor(bookingId);
        if (latest.isEmpty()) {
            log.warn("Timeline state: outcome=[NO_STATE_TO_PUBLISH] bookingId=[{}]", bookingId);
            return;
        }

        BookingStateSnapshotEnvelope snapshot = BookingStateSnapshotMapper.toEnvelope(latest.get(), clock.instant());
        try {
            SendResult<String, Object> result = kafkaTemplate
                    .send(TOPIC_AUDIT_TIMELINE, bookingId.toString(), snapshot)
                    .get(PUBLISH_WAIT_SECONDS, TimeUnit.SECONDS);
            log.info("Timeline state: outcome=[TIMELINE_STATE_PUBLISHED] bookingId=[{}] sourceEventId=[{}] "
                            + "derivedEventId=[{}] currentStatus=[{}] partition=[{}] offset=[{}]",
                    bookingId, snapshot.payload().sourceEventId(), snapshot.eventId(),
                    snapshot.payload().currentStatus(), partitionOf(result), offsetOf(result));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failed(bookingId, snapshot, ex);
        } catch (Exception ex) {
            throw failed(bookingId, snapshot, ex);
        }
    }

    private static TimelinePublishException failed(UUID bookingId, BookingStateSnapshotEnvelope snapshot, Exception ex) {
        log.error("Timeline state: outcome=[TIMELINE_STATE_PUBLISH_FAILED] bookingId=[{}] sourceEventId=[{}] "
                        + "derivedEventId=[{}] exceptionClass=[{}] exceptionMessage=[{}]",
                bookingId, snapshot.payload().sourceEventId(), snapshot.eventId(),
                ex.getClass().getName(), ex.getMessage());
        return new TimelinePublishException(bookingId.toString(), ex);
    }

    private static Object partitionOf(SendResult<String, Object> result) {
        return result == null || result.getRecordMetadata() == null ? null : result.getRecordMetadata().partition();
    }

    private static Object offsetOf(SendResult<String, Object> result) {
        return result == null || result.getRecordMetadata() == null ? null : result.getRecordMetadata().offset();
    }
}
