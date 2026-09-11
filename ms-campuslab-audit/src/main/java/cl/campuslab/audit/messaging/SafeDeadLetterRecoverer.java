package cl.campuslab.audit.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * kafka-admin never auto-creates the DLT for audit (design doc §5.4/§10 open question
 * 4 - "producers and consumers assume it already exists and fail loudly if it
 * doesn't"). If the DLT publish itself fails (topic missing because kafka-admin never
 * ran), this thin wrapper catches it, logs ERROR, and does NOT rethrow - this keeps the
 * container consuming subsequent records at the accepted cost of losing that one
 * event's audit trail until kafka-admin is fixed and the source event is manually
 * replayed from {@code bookings.events}' own retention window if still available.
 */
public class SafeDeadLetterRecoverer implements ConsumerRecordRecoverer {

    private static final Logger log = LoggerFactory.getLogger(SafeDeadLetterRecoverer.class);

    private final DeadLetterPublishingRecoverer delegate;

    public SafeDeadLetterRecoverer(DeadLetterPublishingRecoverer delegate) {
        this.delegate = delegate;
    }

    @Override
    public void accept(ConsumerRecord<?, ?> record, Exception exception) {
        log.warn("Dead-lettering record: topic=[{}] partition=[{}] offset=[{}] reason=[{}]",
                record.topic(), record.partition(), record.offset(), reasonFor(exception));
        try {
            delegate.accept(record, exception);
        } catch (Exception dltPublishFailure) {
            log.error("DLT publish failed, message will not be retried, audit trail gap for this event: "
                            + "topic=[{}] partition=[{}] offset=[{}] exceptionClass=[{}] exceptionMessage=[{}]",
                    record.topic(), record.partition(), record.offset(),
                    dltPublishFailure.getClass().getName(), dltPublishFailure.getMessage());
        }
    }

    /**
     * design doc §9 AC8's own required WARN reason tags. Classified by construction,
     * not by exhaustively matching every possible DB-failure exception type: {@code
     * auditErrorHandler} (KafkaConsumerConfig) marks only {@link UnparseableEnvelopeException}/
     * {@link UnrecognizedEventTypeException} (plus the deserializer's own {@link
     * DeserializationException}) as non-retryable poison messages. Anything else that
     * reaches this recoverer necessarily traveled the retryable path and exhausted
     * {@code FixedBackOff(1000, 2)} - so it is a DB failure by construction, whether it
     * surfaces as a {@code DataAccessException} (mid-transaction) or a {@code
     * CannotCreateTransactionException} (a {@code TransactionException}, thrown at
     * transaction-start when Postgres is unreachable, not a DataAccessException
     * subtype - the bug QA caught live).
     */
    static String reasonFor(Exception exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof DeserializationException) {
                return "UNPARSEABLE_ENVELOPE";
            }
            if (cause instanceof UnparseableEnvelopeException) {
                return "UNPARSEABLE_ENVELOPE";
            }
            if (cause instanceof UnrecognizedEventTypeException) {
                return "UNRECOGNIZED_TYPE";
            }
            cause = cause.getCause();
        }
        return "DB_RETRY_EXHAUSTED";
    }
}
