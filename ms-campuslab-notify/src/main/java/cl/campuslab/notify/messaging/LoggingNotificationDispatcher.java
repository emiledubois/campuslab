package cl.campuslab.notify.messaging;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The sole production {@link NotificationDispatcher} - today's simulated-send log line,
 * unchanged in substance from before this slice (design doc §7.1): still no real I/O, a
 * single INFO log line standing in for an SMTP/push call. {@code simulatedActionFor}/
 * {@code recipientFor} are moved here, verbatim, from {@code NotificationProcessor} -
 * computing "what the send would have done" is a dispatch concern, not a
 * parse/dedup concern.
 */
@Component
public class LoggingNotificationDispatcher implements NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationDispatcher.class);

    @Override
    public void dispatch(NotificationType type, NotificationEnvelope envelope) {
        String action = simulatedActionFor(type);
        String recipient = recipientFor(type, envelope);
        log.info("Notification dispatched: type=[{}] eventId=[{}] correlationId=[{}] action=[{}] recipient=[{}]",
                type, envelope.eventId(), envelope.correlationId(), action, recipient);
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
