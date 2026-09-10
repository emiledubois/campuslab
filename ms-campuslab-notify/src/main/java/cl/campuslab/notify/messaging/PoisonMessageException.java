package cl.campuslab.notify.messaging;

/**
 * The only two failure modes in this slice's scope (design doc §5.3) - both deterministic,
 * neither retriable: the message body can't be parsed as the expected envelope shape (or
 * is missing a required envelope field), or it parses fine but {@code type} isn't one of
 * the recognized values for the queue it arrived on. Thrown from a {@code @RabbitListener}
 * method, this results in {@code basic.nack(requeue=false)} (the container factory's
 * {@code default-requeue-rejected: false}), which RabbitMQ then dead-letters per the
 * queue's configured DLX - no application-level retry loop anywhere.
 */
public class PoisonMessageException extends RuntimeException {

    public enum Reason {
        UNPARSEABLE_ENVELOPE,
        UNRECOGNIZED_TYPE
    }

    private final Reason reason;

    public PoisonMessageException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
