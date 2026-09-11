package cl.campuslab.audit.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code bookings.events} only (design doc §5.4) - does NOT consume {@code
 * audit.timeline} (§2.1, nothing does this slice). Consumer group {@code audit-service}
 * (bound via {@code containerFactory}'s own consumer factory group-id config, not
 * repeated here, so the two never drift apart).
 */
@Component
public class BookingEventListener {

    private final TimelineIngestionService ingestionService;

    public BookingEventListener(TimelineIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @KafkaListener(topics = "bookings.events", containerFactory = "auditKafkaListenerContainerFactory")
    public void onBookingEvent(BookingStreamEnvelope envelope) {
        ingestionService.ingest(envelope);
    }
}
