package cl.campuslab.audit.messaging;

/**
 * Deterministic/poison classification (design doc §5.4) - a payload that deserialized
 * (so {@code ErrorHandlingDeserializer} never saw a problem) but is missing a required
 * envelope field. Registered as non-retryable, same reasoning as {@link
 * UnrecognizedEventTypeException}.
 */
public class UnparseableEnvelopeException extends RuntimeException {

    public UnparseableEnvelopeException(String reason) {
        super("Unparseable bookings.events envelope: " + reason);
    }
}
