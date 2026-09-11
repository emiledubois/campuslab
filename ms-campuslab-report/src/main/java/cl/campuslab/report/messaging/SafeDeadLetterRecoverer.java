package cl.campuslab.report.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.DeserializationException;

/**
 * kafka-admin never auto-creates the DLT for report (design doc §5.3, same accepted
 * gap as audit's own). If the DLT publish itself fails (topic missing because
 * kafka-admin never ran), this thin wrapper catches it, logs ERROR, and does NOT
 * rethrow - this keeps the container consuming subsequent records at the accepted
 * cost of losing that one event's KPI contribution until kafka-admin is fixed.
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
            log.error("DLT publish failed, message will not be retried, KPI gap for this event: "
                            + "topic=[{}] partition=[{}] offset=[{}] exceptionClass=[{}] exceptionMessage=[{}]",
                    record.topic(), record.partition(), record.offset(),
                    dltPublishFailure.getClass().getName(), dltPublishFailure.getMessage());
        }
    }

    /**
     * design doc §2.4's corrected classification, applied from day one (the bug QA
     * caught in {@code kafka-audit.md}, iteration 1): {@code
     * ReportKafkaConsumerConfig#reportErrorHandler} marks only {@link
     * UnparseableEnvelopeException}/{@link UnrecognizedEventTypeException} (plus the
     * deserializer's own {@link DeserializationException}) as non-retryable poison
     * messages. Anything else that reaches this recoverer necessarily traveled the
     * retryable path and exhausted {@code FixedBackOff(1000, 2)} - so it is a DB
     * failure by construction, whether it surfaces as a {@code DataAccessException}
     * (mid-transaction) or a {@code CannotCreateTransactionException} (a {@code
     * TransactionException}, thrown at transaction-start when Postgres is
     * unreachable, not a DataAccessException subtype).
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
