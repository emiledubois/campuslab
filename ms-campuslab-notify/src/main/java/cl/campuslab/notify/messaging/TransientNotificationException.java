package cl.campuslab.notify.messaging;

/**
 * Thrown by a {@link NotificationDispatcher} implementation when one attempt to dispatch
 * a notification fails in a way a retry might recover from (design doc §5.4/§7.3) - the
 * only failure mode in this slice's scope that {@link NotificationDeliveryHandler}
 * classifies as retryable-by-exclusion (anything that is not a {@link
 * PoisonMessageException}). Always carries a non-null correlationId: by the time
 * dispatch() is ever reached, {@code NotificationProcessor} has already parsed the
 * envelope successfully, so correlationId is always known.
 */
public class TransientNotificationException extends RuntimeException {

    private final String correlationId;

    public TransientNotificationException(String message, String correlationId) {
        super(message);
        this.correlationId = correlationId;
    }

    public TransientNotificationException(String message, Throwable cause, String correlationId) {
        super(message, cause);
        this.correlationId = correlationId;
    }

    public String correlationId() {
        return correlationId;
    }
}
