package cl.campuslab.audit.messaging;

import cl.campuslab.audit.domain.TimelineEvent;
import cl.campuslab.audit.domain.TimelineEventRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clase aparte para que la lectura pase por el proxy transaccional: corre en su propia
 * transaccion de solo lectura, nunca en la de la ingesta, y no mantiene una conexion
 * abierta mientras se espera a Kafka (audit-timeline-compacted.md §5.3.4 paso 2a).
 */
@Component
public class TimelineStateReader {

    private final TimelineEventRepository repository;

    public TimelineStateReader(TimelineEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<TimelineEvent> latestFor(UUID bookingId) {
        return repository.findFirstByBookingIdOrderByOccurredAtDescReceivedAtDesc(bookingId);
    }
}
