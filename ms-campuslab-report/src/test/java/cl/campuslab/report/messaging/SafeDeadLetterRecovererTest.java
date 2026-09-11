package cl.campuslab.report.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.sql.SQLTransientConnectionException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * design doc §2.4's corrected classification, applied here from day one instead of
 * repeating {@code kafka-audit.md}'s QA iteration-1 bug: a sustained Postgres outage
 * at transaction-START time surfaces as a {@code CannotCreateTransactionException} (a
 * {@code TransactionException}), not a {@code DataAccessException} - the recoverer's
 * reason classification must label it DB_RETRY_EXHAUSTED regardless, since {@code
 * KafkaConsumerConfig#reportErrorHandler} only ever marks the two poison-message
 * exceptions as non-retryable; anything else reaching this recoverer exhausted the
 * DB-retryable path by construction.
 */
class SafeDeadLetterRecovererTest {

    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("bookings.events", 0, 42L, "key", "value");

    @Test
    void reasonFor_cannotCreateTransactionException_classifiedAsDbRetryExhausted() {
        CannotCreateTransactionException exception =
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction",
                        new SQLTransientConnectionException("connection is not available"));

        String reason = SafeDeadLetterRecoverer.reasonFor(exception);

        assertThat(reason).isEqualTo("DB_RETRY_EXHAUSTED");
    }

    @Test
    void reasonFor_dataAccessException_classifiedAsDbRetryExhausted() {
        DataAccessResourceFailureException exception = new DataAccessResourceFailureException("connection refused");

        String reason = SafeDeadLetterRecoverer.reasonFor(exception);

        assertThat(reason).isEqualTo("DB_RETRY_EXHAUSTED");
    }

    @Test
    void reasonFor_transactionExceptionWrappedDeeperInCauseChain_stillClassifiedAsDbRetryExhausted() {
        RuntimeException exception = new RuntimeException("listener invocation failed",
                new CannotCreateTransactionException("could not open connection", new RuntimeException("timeout")));

        String reason = SafeDeadLetterRecoverer.reasonFor(exception);

        assertThat(reason).isEqualTo("DB_RETRY_EXHAUSTED");
    }

    @Test
    void reasonFor_deserializationException_classifiedAsUnparseableEnvelope() {
        DeserializationException exception =
                new DeserializationException("failed to deserialize", new byte[0], false, new RuntimeException("bad json"));

        String reason = SafeDeadLetterRecoverer.reasonFor(exception);

        assertThat(reason).isEqualTo("UNPARSEABLE_ENVELOPE");
    }

    @Test
    void reasonFor_unparseableEnvelopeException_classifiedAsUnparseableEnvelope() {
        UnparseableEnvelopeException exception = new UnparseableEnvelopeException("missing bookingId");

        String reason = SafeDeadLetterRecoverer.reasonFor(exception);

        assertThat(reason).isEqualTo("UNPARSEABLE_ENVELOPE");
    }

    @Test
    void reasonFor_unrecognizedEventTypeException_classifiedAsUnrecognizedType() {
        UnrecognizedEventTypeException exception = new UnrecognizedEventTypeException("NOT_A_REAL_TYPE");

        String reason = SafeDeadLetterRecoverer.reasonFor(exception);

        assertThat(reason).isEqualTo("UNRECOGNIZED_TYPE");
    }

    @Test
    void accept_delegatesToDeadLetterPublishingRecoverer() {
        DeadLetterPublishingRecoverer delegate = mock(DeadLetterPublishingRecoverer.class);
        SafeDeadLetterRecoverer recoverer = new SafeDeadLetterRecoverer(delegate);
        CannotCreateTransactionException exception = new CannotCreateTransactionException("could not open connection");

        recoverer.accept(record, exception);

        verify(delegate).accept(record, exception);
    }

    @Test
    void accept_dltPublishItselfFails_doesNotRethrow() {
        DeadLetterPublishingRecoverer delegate = mock(DeadLetterPublishingRecoverer.class);
        RuntimeException exception = new RuntimeException("unused");
        doThrow(new RuntimeException("DLT topic does not exist")).when(delegate).accept(record, exception);
        SafeDeadLetterRecoverer recoverer = new SafeDeadLetterRecoverer(delegate);

        assertThatCode(() -> recoverer.accept(record, exception)).doesNotThrowAnyException();
    }
}
