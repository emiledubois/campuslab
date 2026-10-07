package cl.campuslab.notify.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shared by both {@code @RabbitListener} methods, via {@link NotificationDeliveryHandler}
 * (design doc §5.3/§5.4) - parses the envelope, validates it against the queue's own
 * allowed {@code type} set, deduplicates on {@code eventId}, and delegates the actual
 * "send" step to {@link NotificationDispatcher}. Dedup-then-dispatch-then-mark ordering
 * is load-bearing (§5.4/§9 A04): {@code markProcessed} is only ever reached after {@code
 * dispatch} returns without throwing, so a message that is retried transiently and later
 * succeeds is marked exactly once, and a message that never successfully dispatches
 * (poison, or retries exhausted) is never marked - its eventId stays eligible for a
 * later, legitimate reprocessing.
 */
@Service
public class NotificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);

    private final ObjectMapper objectMapper;
    private final DedupCache dedupCache;
    private final NotificationDispatcher dispatcher;

    public NotificationProcessor(ObjectMapper objectMapper, DedupCache dedupCache, NotificationDispatcher dispatcher) {
        this.objectMapper = objectMapper;
        this.dedupCache = dedupCache;
        this.dispatcher = dispatcher;
    }

    /**
     * Returns the envelope's correlationId on success (fresh or duplicate) so {@link
     * NotificationDeliveryHandler} can log it on the "acked" line without re-parsing the
     * body itself. Throws {@link PoisonMessageException} (non-retryable) or whatever
     * {@link RuntimeException} the dispatcher throws (retryable by exclusion) on failure -
     * both propagate uncaught, for the handler to classify.
     */
    public String process(byte[] body, String queueName, Set<NotificationType> allowedTypes) {
        NotificationEnvelope envelope = parseEnvelope(body, queueName);
        NotificationType type = resolveType(envelope, allowedTypes, queueName);

        if (dedupCache.isDuplicate(envelope.eventId())) {
            log.info("Notification duplicate: type=[{}] eventId=[{}] correlationId=[{}] outcome=[DUPLICATE_SKIPPED]",
                    type, envelope.eventId(), envelope.correlationId());
            return envelope.correlationId();
        }

        dispatcher.dispatch(type, envelope);

        log.info("Notification processed: type=[{}] eventId=[{}] correlationId=[{}] outcome=[FRESH]",
                type, envelope.eventId(), envelope.correlationId());
        dedupCache.markProcessed(envelope.eventId());
        return envelope.correlationId();
    }

    private NotificationEnvelope parseEnvelope(byte[] body, String queueName) {
        NotificationEnvelope envelope;
        try {
            envelope = objectMapper.readValue(body, NotificationEnvelope.class);
        } catch (IOException ex) {
            log.warn("Poison message: reason=[UNPARSEABLE_ENVELOPE] queue=[{}] detail=[{}]", queueName, ex.getMessage());
            throw new PoisonMessageException(
                    PoisonMessageException.Reason.UNPARSEABLE_ENVELOPE, "Message body is not a valid envelope.", null);
        }
        if (envelope == null || !envelope.hasAllRequiredFields()) {
            log.warn("Poison message: reason=[UNPARSEABLE_ENVELOPE] queue=[{}] eventId=[{}]",
                    queueName, envelope != null ? envelope.eventId() : null);
            throw new PoisonMessageException(
                    PoisonMessageException.Reason.UNPARSEABLE_ENVELOPE, "Envelope is missing a required field.",
                    envelope != null ? envelope.correlationId() : null);
        }
        return envelope;
    }

    private NotificationType resolveType(NotificationEnvelope envelope, Set<NotificationType> allowedTypes, String queueName) {
        NotificationType type = NotificationType.fromValue(envelope.type());
        if (type == null || !allowedTypes.contains(type)) {
            log.warn("Poison message: reason=[UNRECOGNIZED_TYPE] queue=[{}] eventId=[{}] rawType=[{}]",
                    queueName, envelope.eventId(), envelope.type());
            throw new PoisonMessageException(
                    PoisonMessageException.Reason.UNRECOGNIZED_TYPE, "Unrecognized notification type: " + envelope.type(),
                    envelope.correlationId());
        }
        return type;
    }
}
