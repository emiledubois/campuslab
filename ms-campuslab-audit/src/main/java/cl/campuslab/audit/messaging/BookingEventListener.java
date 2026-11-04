package cl.campuslab.audit.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code bookings.events} (design doc §5.4). Consumer group {@code audit-service}
 * (bound via {@code containerFactory}'s own consumer factory group-id config, not
 * repeated here, so the two never drift apart). Despues de persistir, publica el estado
 * actual de la reserva en {@code audit.timeline} (audit-timeline-compacted.md §5.3.4). No
 * es {@code @Transactional}: la ingesta hace commit antes de publicar, y un fallo de
 * publicacion se propaga para que el container reintente.
 */
@Component
public class BookingEventListener {

    private final TimelineIngestionService ingestionService;
    private final TimelineStatePublisher timelineStatePublisher;

    public BookingEventListener(TimelineIngestionService ingestionService, TimelineStatePublisher timelineStatePublisher) {
        this.ingestionService = ingestionService;
        this.timelineStatePublisher = timelineStatePublisher;
    }

    @KafkaListener(topics = "bookings.events", containerFactory = "auditKafkaListenerContainerFactory")
    public void onBookingEvent(BookingStreamEnvelope envelope) {
        ingestionService.ingest(envelope);
        timelineStatePublisher.publishCurrentState(envelope.payload().bookingId());
    }
}
