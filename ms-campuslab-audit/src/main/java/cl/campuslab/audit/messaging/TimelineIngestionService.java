package cl.campuslab.audit.messaging;

import cl.campuslab.audit.domain.TimelineEvent;
import cl.campuslab.audit.domain.TimelineEventRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real idempotency (design doc §5.4/§9 AC6) - {@code event_id}'s {@code UNIQUE}
 * constraint plus an {@code existsByEventId} check before insert, inside the same
 * short transaction as the write. Unlike notify's bounded in-memory cache, this
 * survives a consumer restart, because the durability lives in Postgres.
 */
@Service
public class TimelineIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TimelineIngestionService.class);

    private final TimelineEventRepository repository;

    public TimelineIngestionService(TimelineEventRepository repository) {
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
            log.info("Timeline event duplicate: eventId=[{}] bookingId=[{}] outcome=[DUPLICATE_SKIPPED]",
                    envelope.eventId(), envelope.payload().bookingId());
            return;
        }

        TimelineEvent event = new TimelineEvent(
                envelope.eventId(),
                envelope.payload().bookingId(),
                envelope.payload().resourceId(),
                envelope.type(),
                envelope.payload().actorOid(),
                joinRoles(envelope.payload().actorRoles()),
                envelope.payload().studentOid(),
                envelope.payload().fromStatus(),
                envelope.payload().toStatus(),
                envelope.traceId(),
                envelope.correlationId(),
                envelope.timestamp());

        try {
            repository.save(event);
            log.info("Timeline event persisted: eventId=[{}] bookingId=[{}] eventType=[{}]",
                    envelope.eventId(), envelope.payload().bookingId(), envelope.type());
        } catch (DataIntegrityViolationException ex) {
            // A genuine race lost against the unique constraint itself (two redeliveries
            // processed concurrently) - this is a duplicate, not a transient failure, so
            // it is never retried (design doc §5.4's idempotency guarantee holds either way).
            log.info("Timeline event duplicate: eventId=[{}] bookingId=[{}] outcome=[DUPLICATE_SKIPPED]",
                    envelope.eventId(), envelope.payload().bookingId());
        }
    }

    private static String joinRoles(List<String> roles) {
        return roles == null || roles.isEmpty() ? "" : String.join(",", roles);
    }
}
