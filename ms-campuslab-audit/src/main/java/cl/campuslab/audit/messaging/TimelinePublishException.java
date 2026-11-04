package cl.campuslab.audit.messaging;

/**
 * Fallo al publicar en {@code audit.timeline} (audit-timeline-compacted.md §5.3.4).
 * Reintentable a proposito: no se registra en {@code addNotRetryableExceptions}.
 */
public class TimelinePublishException extends RuntimeException {

    public TimelinePublishException(String bookingId, Throwable cause) {
        super("Failed to publish audit.timeline snapshot for bookingId " + bookingId, cause);
    }
}
