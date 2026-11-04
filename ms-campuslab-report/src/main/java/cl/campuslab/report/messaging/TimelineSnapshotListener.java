package cl.campuslab.report.messaging;

import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Consume {@code audit.timeline} en el group {@code report-timeline-projection}
 * (audit-timeline-compacted.md §5.4.2/§5.6). Si la proyeccion esta vacia al recibir
 * particiones, vuelve al inicio del topico compactado para reconstruirla, aunque el group
 * ya tenga offsets comprometidos (ahi {@code auto.offset.reset} no aplica). Asume una sola
 * instancia de report.
 */
@Component
public class TimelineSnapshotListener implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(TimelineSnapshotListener.class);

    private final BookingStateProjectionService projectionService;

    public TimelineSnapshotListener(BookingStateProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    // required = false en la key: una key nula debe llegar al servicio y clasificarse como
    // poison no reintentable, no fallar la resolucion del argumento y reintentarse.
    @KafkaListener(id = "timelineProjection", idIsGroup = false, topics = "audit.timeline",
            containerFactory = "timelineProjectionKafkaListenerContainerFactory")
    public void onSnapshot(@Payload(required = false) BookingStateSnapshotEnvelope envelope,
                           @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key) {
        projectionService.apply(key, envelope);
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        boolean empty;
        try {
            empty = projectionService.isEmpty();
        } catch (Exception ex) {
            log.warn("Booking state projection: outcome=[REBUILD_CHECK_FAILED] partitions=[{}] exceptionClass=[{}] "
                    + "exceptionMessage=[{}]", assignments.keySet(), ex.getClass().getName(), ex.getMessage());
            return;
        }
        if (empty) {
            log.info("Booking state projection: outcome=[REBUILD_FROM_BEGINNING] partitions=[{}]", assignments.keySet());
            callback.seekToBeginning(assignments.keySet());
        }
    }
}
