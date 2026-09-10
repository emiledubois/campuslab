package cl.campuslab.mqadmin.mq;

/** Maps to 503 (design doc §3) - RabbitMQ was unreachable from mq-admin. */
public class RabbitUnavailableException extends RuntimeException {

    public RabbitUnavailableException(Throwable cause) {
        super("RabbitMQ is currently unavailable.", cause);
    }
}
