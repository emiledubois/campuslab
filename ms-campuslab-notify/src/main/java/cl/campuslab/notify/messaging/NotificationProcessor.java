package cl.campuslab.notify.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Shared by both {@code @RabbitListener} methods (design doc §5.3/§5.4) - parses the
 * envelope, validates it against the queue's own allowed {@code type} set, deduplicates
 * on {@code eventId}, and logs the simulated notification action. Never performs real
 * I/O beyond a log statement (§5.4 - simulated/logged, no SMTP/push provider), so the
 * only failure modes here are the two deterministic poison-message cases.
 */
@Service
public class NotificationProcessor {

    private static final Logger log = LoggerFactory.getLogger(NotificationProcessor.class);

    private final ObjectMapper objectMapper;
    private final DedupCache dedupCache;

    public NotificationProcessor(ObjectMapper objectMapper, DedupCache dedupCache) {
        this.objectMapper = objectMapper;
        this.dedupCache = dedupCache;
    }

    public void process(byte[] body, String queueName, Set<NotificationType> allowedTypes) {
        NotificationEnvelope envelope = parseEnvelope(body, queueName);
        NotificationType type = resolveType(envelope, allowedTypes, queueName);

        if (dedupCache.isDuplicate(envelope.eventId())) {
            log.info("Notification duplicate: type=[{}] eventId=[{}] correlationId=[{}] outcome=[DUPLICATE_SKIPPED]",
                    type, envelope.eventId(), envelope.correlationId());
            return;
        }

        String action = simulatedActionFor(type);
        String recipient = recipientFor(type, envelope);
        log.info("Notification processed: type=[{}] eventId=[{}] correlationId=[{}] action=[{}] recipient=[{}] outcome=[FRESH]",
                type, envelope.eventId(), envelope.correlationId(), action, recipient);

        dedupCache.markProcessed(envelope.eventId());
    }

    private NotificationEnvelope parseEnvelope(byte[] body, String queueName) {
        NotificationEnvelope envelope;
        try {
            envelope = objectMapper.readValue(body, NotificationEnvelope.class);
        } catch (IOException ex) {
            log.warn("Poison message: reason=[UNPARSEABLE_ENVELOPE] queue=[{}] detail=[{}]", queueName, ex.getMessage());
            throw new PoisonMessageException(
                    PoisonMessageException.Reason.UNPARSEABLE_ENVELOPE, "Message body is not a valid envelope.");
        }
        if (envelope == null || !envelope.hasAllRequiredFields()) {
            log.warn("Poison message: reason=[UNPARSEABLE_ENVELOPE] queue=[{}] eventId=[{}]",
                    queueName, envelope != null ? envelope.eventId() : null);
            throw new PoisonMessageException(
                    PoisonMessageException.Reason.UNPARSEABLE_ENVELOPE, "Envelope is missing a required field.");
        }
        return envelope;
    }

    private NotificationType resolveType(NotificationEnvelope envelope, Set<NotificationType> allowedTypes, String queueName) {
        NotificationType type = NotificationType.fromValue(envelope.type());
        if (type == null || !allowedTypes.contains(type)) {
            log.warn("Poison message: reason=[UNRECOGNIZED_TYPE] queue=[{}] eventId=[{}] rawType=[{}]",
                    queueName, envelope.eventId(), envelope.type());
            throw new PoisonMessageException(
                    PoisonMessageException.Reason.UNRECOGNIZED_TYPE, "Unrecognized notification type: " + envelope.type());
        }
        return type;
    }

    private static String simulatedActionFor(NotificationType type) {
        return type == NotificationType.PREP_TICKET_REQUESTED ? "PREP_TICKET_CREATED" : "EMAIL_SENT";
    }

    /** Never a real name/email/address (design doc §5.2/§5.4) - the pseudonymous Entra
     * {@code oid}, or "tecnico" generically for the prep ticket (no assigned technician
     * concept exists in this slice's payload). */
    private static String recipientFor(NotificationType type, NotificationEnvelope envelope) {
        if (type == NotificationType.PREP_TICKET_REQUESTED) {
            return "tecnico";
        }
        Object payload = envelope.payload();
        if (payload instanceof Map<?, ?> map) {
            Object studentOid = map.get("studentOid");
            return studentOid != null ? studentOid.toString() : null;
        }
        return null;
    }
}
