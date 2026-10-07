package cl.campuslab.notify.messaging;

/**
 * The only two failure modes in this slice's scope (design doc §5.3) - both deterministic,
 * neither retriable: the message body can't be parsed as the expected envelope shape (or
 * is missing a required envelope field), or it parses fine but {@code type} isn't one of
 * the recognized values for the queue it arrived on. Caught by {@link
 * NotificationDeliveryHandler} (Slice B), which routes it to {@code basic.nack(requeue=false)}
 * on the very first and only attempt - no retry, since nothing about the message's bytes
 * changes between attempts. RabbitMQ then dead-letters it per the queue's configured DLX.
 */
public class PoisonMessageException extends RuntimeException {

    public enum Reason {
        UNPARSEABLE_ENVELOPE,
        UNRECOGNIZED_TYPE
    }

    private final Reason reason;

    /** Nullable - null when the envelope's bytes never parsed at all, so no field,
     *  including correlationId, is knowable (design doc §7.2/§7.3). */
    private final String correlationId;

    public PoisonMessageException(Reason reason, String message, String correlationId) {
        super(message);
        this.reason = reason;
        this.correlationId = correlationId;
    }

    public Reason reason() {
        return reason;
    }

    public String correlationId() {
        return correlationId;
    }
}
