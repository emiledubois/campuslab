package cl.campuslab.report.messaging;

/**
 * Deterministic/poison classification (design doc §2.4/§5.3) - a parseable envelope
 * whose {@code type} is not one of the six recognized values. Registered as
 * non-retryable with the container's {@code DefaultErrorHandler} (a retry can never
 * succeed since nothing about the message changes on a second attempt).
 */
public class UnrecognizedEventTypeException extends RuntimeException {

    public UnrecognizedEventTypeException(String rawType) {
        super("Unrecognized bookings.events type: " + rawType);
    }
}
