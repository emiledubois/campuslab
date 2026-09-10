package cl.campuslab.mqadmin.mq;

/**
 * Maps to 400 (design doc §3/§7 A03) - {@code dlqName} was not one of the three real,
 * statically allow-listed DLQ names; never passed through to an AMQP call.
 */
public class UnknownDlqException extends RuntimeException {

    public UnknownDlqException(String dlqName) {
        super("'" + dlqName + "' is not a recognized DLQ name.");
    }
}
