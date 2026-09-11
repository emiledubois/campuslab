package cl.campuslab.report.messaging;

import cl.campuslab.report.domain.ReportEvent;
import cl.campuslab.report.domain.ReportEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real idempotency (design doc §2.1/§5.3) - {@code event_id}'s {@code UNIQUE}
 * constraint plus an {@code existsByEventId} check before insert, inside the same
 * short transaction as the write. Unlike notify's bounded in-memory cache, this
 * survives a consumer restart, because the durability lives in Postgres.
 */
@Service
public class ReportIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ReportIngestionService.class);

    private final ReportEventRepository repository;

    public ReportIngestionService(ReportEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void ingest(BookingStreamEnvelope envelope) {
        if (envelope == null || !envelope.hasAllRequiredFields()) {
            throw new UnparseableEnvelopeException("missing a required field");
        }
        if (!BookingStreamEventType.RECOGNIZED.contains(envelope.type())) {
            throw new UnrecognizedEventTypeException(envelope.type());
        }

        if (repository.existsByEventId(envelope.eventId())) {
            log.info("Report event duplicate: eventId=[{}] bookingId=[{}] outcome=[DUPLICATE_SKIPPED]",
                    envelope.eventId(), envelope.payload().bookingId());
            return;
        }

        ReportEvent event = new ReportEvent(
                envelope.eventId(),
                envelope.payload().bookingId(),
                envelope.payload().resourceId(),
                envelope.type(),
                envelope.payload().fromStatus(),
                envelope.payload().toStatus(),
                envelope.timestamp());

        try {
            repository.save(event);
            log.info("Report event persisted: eventId=[{}] bookingId=[{}] eventType=[{}]",
                    envelope.eventId(), envelope.payload().bookingId(), envelope.type());
        } catch (DataIntegrityViolationException ex) {
            // A genuine race lost against the unique constraint itself (two redeliveries
            // processed concurrently) - this is a duplicate, not a transient failure, so
            // it is never retried (design doc §2.1's idempotency guarantee holds either way).
            log.info("Report event duplicate: eventId=[{}] bookingId=[{}] outcome=[DUPLICATE_SKIPPED]",
                    envelope.eventId(), envelope.payload().bookingId());
        }
    }
}
