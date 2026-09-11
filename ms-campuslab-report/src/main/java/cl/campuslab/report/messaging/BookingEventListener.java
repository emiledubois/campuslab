package cl.campuslab.report.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code bookings.events} only (design doc §5.3) - a second, independent
 * consumer group reading the same topic audit already reads. Consumer group {@code
 * report-service} (bound via {@code containerFactory}'s own consumer factory
 * group-id config, not repeated here, so the two never drift apart).
 */
@Component
public class BookingEventListener {

    private final ReportIngestionService ingestionService;

    public BookingEventListener(ReportIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @KafkaListener(topics = "bookings.events", containerFactory = "reportKafkaListenerContainerFactory")
    public void onBookingEvent(BookingStreamEnvelope envelope) {
        ingestionService.ingest(envelope);
    }
}
