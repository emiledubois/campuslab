package cl.campuslab.report.messaging;

import cl.campuslab.report.domain.BookingStateProjectionRepository;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proyeccion del estado actual por reserva desde {@code audit.timeline}
 * (audit-timeline-compacted.md §5.4.3). Idempotente por {@code eventId} derivado y
 * resistente a eventos atrasados via {@code upsertIfNewer}.
 */
@Service
public class BookingStateProjectionService {

    private static final Logger log = LoggerFactory.getLogger(BookingStateProjectionService.class);
    private static final String TOPIC = "audit.timeline";
    private static final Set<String> VALID_STATUSES =
            Set.of("SOLICITADA", "APROBADA", "EN_PREPARACION", "EN_USO", "DEVUELTA", "CANCELADA");

    private final BookingStateProjectionRepository repository;

    public BookingStateProjectionService(BookingStateProjectionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void apply(String key, BookingStateSnapshotEnvelope envelope) {
        if (envelope == null || !envelope.hasAllRequiredFields()) {
            throw new UnparseableEnvelopeException(TOPIC, "missing a required field or null value");
        }
        if (!BookingStateSnapshotEnvelope.TYPE.equals(envelope.type())) {
            throw new UnrecognizedEventTypeException(TOPIC, envelope.type());
        }
        BookingStateSnapshotPayload payload = envelope.payload();
        if (!VALID_STATUSES.contains(payload.currentStatus())) {
            throw new UnparseableEnvelopeException(TOPIC, "invalid currentStatus " + payload.currentStatus());
        }
        // Una key distinta del bookingId romperia la semantica de compactacion (§7 A08).
        if (key == null || !key.equals(payload.bookingId().toString())) {
            throw new UnparseableEnvelopeException(TOPIC, "record key does not match payload.bookingId");
        }

        if (repository.existsByLastEventId(envelope.eventId())) {
            logOutcome("DUPLICATE_SKIPPED", envelope);
            return;
        }

        try {
            int applied = repository.upsertIfNewer(payload.bookingId(), payload.resourceId(), payload.currentStatus(),
                    payload.lastTransitionAt(), envelope.eventId());
            logOutcome(applied == 1 ? "APPLIED" : "STALE_SKIPPED", envelope);
        } catch (DataIntegrityViolationException ex) {
            logOutcome("DUPLICATE_SKIPPED", envelope);
        }
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return repository.count() == 0;
    }

    private static void logOutcome(String outcome, BookingStateSnapshotEnvelope envelope) {
        log.info("Booking state projection: outcome=[{}] bookingId=[{}] eventId=[{}] currentStatus=[{}] lastTransitionAt=[{}]",
                outcome, envelope.payload().bookingId(), envelope.eventId(), envelope.payload().currentStatus(),
                envelope.payload().lastTransitionAt());
    }
}
