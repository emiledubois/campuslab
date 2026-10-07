package cl.campuslab.notify.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cl.campuslab.notify.AbstractIntegrationTest;
import cl.campuslab.notify.ScriptableNotificationDispatcher;
import cl.campuslab.notify.TestDispatcherConfig;
import cl.campuslab.notify.TestTopologyConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Design doc acceptance criteria 1-7 and 9 (notify-manual-ack.md §11), all against a
 * real, running broker: explicit manual ack (AC1), dedup still acks correctly (AC2,
 * regression of the old AC10), poison messages skip retries entirely and dead-letter
 * immediately whether the envelope never parsed (AC3, regression of old AC11) or parsed
 * with an unrecognized type (AC4, regression of old AC11's second case), a transient
 * failure retries with backoff then succeeds (AC5) or exhausts its budget and dead-
 * letters without corrupting dedup state (AC6), a later redelivery of a DLQ'd message is
 * processed fresh, not as a duplicate (AC7), and a poison message never blocks
 * subsequent processing, now with concurrency > 1 (AC9, strengthens old AC12). AC8
 * (prefetch/concurrency actually applied) lives in {@code
 * NotificationListenerContainerConfigTest}; AC10 (retry decision logic in isolation)
 * lives in {@code NotificationDeliveryHandlerTest}.
 */
@SpringBootTest
@Import({TestTopologyConfig.class, TestDispatcherConfig.class})
class NotificationListenersIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private DedupCache dedupCache;

    @Autowired
    private ScriptableNotificationDispatcher scriptableDispatcher;

    private final List<ListAppender<ILoggingEvent>> logAppenders = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rabbitAdmin.purgeQueue("q.cmd.email");
        rabbitAdmin.purgeQueue("q.cmd.prep");
        rabbitAdmin.purgeQueue("q.cmd.email.dlq");
        rabbitAdmin.purgeQueue("q.cmd.prep.dlq");

        attachAppender(NotificationProcessor.class);
        attachAppender(NotificationDeliveryHandler.class);
    }

    @AfterEach
    void tearDown() {
        detachAppenders();
    }

    @Test
    void validMessage_isProcessedAndAckedExplicitly() {
        String eventId = UUID.randomUUID().toString();
        String correlationId = "corr-" + eventId;
        byte[] body = validEnvelope("EMAIL_APPROVED", eventId, correlationId);

        rabbitTemplate.send("", "q.cmd.email", new Message(body, new MessageProperties()));

        await().atMost(Duration.ofSeconds(5)).until(() -> dedupCache.isDuplicate(eventId));
        await().atMost(Duration.ofSeconds(5)).until(() -> depthOf("q.cmd.email") == 0);

        assertThat(depthOf("q.cmd.email.dlq")).isZero();
        assertThat(countLogsMatching(line -> line.contains(correlationId) && line.contains("outcome=[FRESH]")))
                .isEqualTo(1);
        assertThat(countLogsMatching(line -> line.contains("Notification acked") && line.contains(correlationId)))
                .isEqualTo(1);
    }

    @Test
    void redeliveredMessage_isDedupedAndAckedBothTimes() {
        String eventId = UUID.randomUUID().toString();
        String correlationId = "corr-" + eventId;
        byte[] body = validEnvelope("EMAIL_APPROVED", eventId, correlationId);

        publishToDirectExchange("email.send", body);
        await().atMost(Duration.ofSeconds(5)).until(() -> dedupCache.isDuplicate(eventId));

        // AC2/old-AC10's "redeliver the identical message" - republished directly onto the
        // same queue with the identical eventId, standing in for a broker-level redelivery.
        rabbitTemplate.send("", "q.cmd.email", new Message(body, new MessageProperties()));
        await().atMost(Duration.ofSeconds(5)).until(() -> countLogsContaining("DUPLICATE_SKIPPED") >= 1);

        assertThat(countLogsMatching(line -> line.contains(correlationId) && line.contains("outcome=[FRESH]")))
                .isEqualTo(1);
        assertThat(countLogsMatching(line -> line.contains(correlationId) && line.contains("outcome=[DUPLICATE_SKIPPED]")))
                .isEqualTo(1);
        // Manual ack didn't break the existing dedup path - both deliveries (the fresh one
        // and the deduped redelivery) are explicitly acked, not left pending.
        assertThat(countLogsMatching(line -> line.contains("Notification acked") && line.contains(correlationId)))
                .isEqualTo(2);
    }

    @Test
    void notValidJson_onEmailQueue_landsInEmailDlqWithZeroRetries() {
        rabbitTemplate.send("", "q.cmd.email", new Message("not valid json".getBytes(), new MessageProperties()));

        await().atMost(Duration.ofSeconds(5)).until(() -> depthOf("q.cmd.email.dlq") == 1);

        assertThat(depthOf("q.cmd.email")).isZero();
        assertThat(countLogsMatching(line -> line.contains("Transient failure, retrying"))).isZero();
        assertThat(countLogsMatching(line ->
                        line.contains("Routing to DLQ, no retry (poison)") && line.contains("correlationId=[UNKNOWN]")))
                .isEqualTo(1);
    }

    @Test
    void unrecognizedType_onPrepQueue_landsInPrepDlqWithZeroRetriesAndKnownCorrelationId() {
        String correlationId = "corr-" + UUID.randomUUID();
        byte[] body = validEnvelope("FOO", UUID.randomUUID().toString(), correlationId);
        rabbitTemplate.send("", "q.cmd.prep", new Message(body, new MessageProperties()));

        await().atMost(Duration.ofSeconds(5)).until(() -> depthOf("q.cmd.prep.dlq") == 1);

        assertThat(depthOf("q.cmd.prep")).isZero();
        assertThat(countLogsMatching(line -> line.contains("Transient failure, retrying"))).isZero();
        assertThat(countLogsMatching(line ->
                        line.contains("Routing to DLQ, no retry (poison)") && line.contains("correlationId=[" + correlationId + "]")))
                .isEqualTo(1);
    }

    @Test
    void transientFailure_retriedTwiceThenSucceeds_isAckedAndMarkedProcessedExactlyOnce() {
        String eventId = UUID.randomUUID().toString();
        String correlationId = "corr-" + eventId;
        scriptableDispatcher.failNextCalls(eventId, 2);
        byte[] body = validEnvelope("EMAIL_APPROVED", eventId, correlationId);

        rabbitTemplate.send("", "q.cmd.email", new Message(body, new MessageProperties()));

        await().atMost(Duration.ofSeconds(5)).until(() -> dedupCache.isDuplicate(eventId));

        assertThat(countLogsMatching(line ->
                        line.contains("Transient failure, retrying") && line.contains("attempt=[1]") && line.contains(correlationId)))
                .isEqualTo(1);
        assertThat(countLogsMatching(line ->
                        line.contains("Transient failure, retrying") && line.contains("attempt=[2]") && line.contains(correlationId)))
                .isEqualTo(1);
        assertThat(countLogsMatching(line -> line.contains("outcome=[FRESH]") && line.contains(correlationId)))
                .isEqualTo(1);
        assertThat(countLogsMatching(line -> line.contains("Notification acked") && line.contains(correlationId)))
                .isEqualTo(1);
        assertThat(dedupCache.isDuplicate(eventId)).isTrue();
    }

    @Test
    void transientFailure_alwaysFailing_exhaustsRetryBudgetAndLandsInDlqWithoutCorruptingDedupState() {
        String eventId = UUID.randomUUID().toString();
        String correlationId = "corr-" + eventId;
        scriptableDispatcher.failNextCalls(eventId, Integer.MAX_VALUE);
        byte[] body = validEnvelope("EMAIL_APPROVED", eventId, correlationId);

        rabbitTemplate.send("", "q.cmd.email", new Message(body, new MessageProperties()));

        await().atMost(Duration.ofSeconds(5)).until(() -> depthOf("q.cmd.email.dlq") == 1);

        assertThat(depthOf("q.cmd.email")).isZero();
        // Test resources/application.yml keeps the default max-attempts=4 (only the
        // backoff durations are shortened for test speed) - 3 retries (attempts 1-3) then
        // exhausted on attempt 4.
        assertThat(countLogsMatching(line -> line.contains("Transient failure, retrying") && line.contains(correlationId)))
                .isEqualTo(3);
        assertThat(countLogsMatching(line -> line.contains("Retries exhausted, routing to DLQ") && line.contains(correlationId)))
                .isEqualTo(1);
        assertThat(dedupCache.isDuplicate(eventId)).isFalse();
    }

    @Test
    void messageThatExhaustedRetries_whenRedeliveredLater_isProcessedFreshNotAsDuplicate() {
        String eventId = UUID.randomUUID().toString();
        String correlationId = "corr-" + eventId;
        scriptableDispatcher.failNextCalls(eventId, Integer.MAX_VALUE);
        byte[] body = validEnvelope("EMAIL_APPROVED", eventId, correlationId);
        rabbitTemplate.send("", "q.cmd.email", new Message(body, new MessageProperties()));
        await().atMost(Duration.ofSeconds(5)).until(() -> depthOf("q.cmd.email.dlq") == 1);
        assertThat(dedupCache.isDuplicate(eventId)).isFalse();

        // Standing in for mq-admin's guarded requeue-from-DLQ (untouched by this slice) -
        // republish the identical body directly onto the original work queue, now with the
        // dispatcher configured to succeed.
        scriptableDispatcher.failNextCalls(eventId, 0);
        rabbitTemplate.send("", "q.cmd.email", new Message(body, new MessageProperties()));

        await().atMost(Duration.ofSeconds(5)).until(() -> dedupCache.isDuplicate(eventId));
        assertThat(countLogsMatching(line -> line.contains(correlationId) && line.contains("outcome=[FRESH]")))
                .isEqualTo(1);
        assertThat(countLogsMatching(line -> line.contains(correlationId) && line.contains("outcome=[DUPLICATE_SKIPPED]")))
                .isZero();
    }

    @Test
    void poisonMessage_doesNotBlockSubsequentProcessingAcrossBothQueues() {
        rabbitTemplate.send("", "q.cmd.email", new Message("not valid json".getBytes(), new MessageProperties()));
        await().atMost(Duration.ofSeconds(5)).until(() -> depthOf("q.cmd.email.dlq") == 1);

        List<String> freshEventIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String eventId = UUID.randomUUID().toString();
            freshEventIds.add(eventId);
            rabbitTemplate.send("", "q.cmd.email",
                    new Message(validEnvelope("EMAIL_ROOM_READY", eventId, "corr-" + eventId), new MessageProperties()));
        }
        String prepEventId = UUID.randomUUID().toString();
        freshEventIds.add(prepEventId);
        rabbitTemplate.send("", "q.cmd.prep",
                new Message(validEnvelope("PREP_TICKET_REQUESTED", prepEventId, "corr-" + prepEventId), new MessageProperties()));

        await().atMost(Duration.ofSeconds(5)).until(() -> freshEventIds.stream().allMatch(dedupCache::isDuplicate));
        assertThat(depthOf("q.cmd.email")).isZero();
        assertThat(depthOf("q.cmd.prep")).isZero();
    }

    private void publishToDirectExchange(String routingKey, byte[] body) {
        rabbitTemplate.send("cmd.direct", routingKey, new Message(body, new MessageProperties()));
    }

    private long depthOf(String queueName) {
        var properties = rabbitAdmin.getQueueProperties(queueName);
        return properties != null ? ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue() : -1;
    }

    private void attachAppender(Class<?> loggerClass) {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(loggerClass)).addAppender(appender);
        logAppenders.add(appender);
    }

    private void detachAppenders() {
        ((Logger) LoggerFactory.getLogger(NotificationProcessor.class)).detachAppender(logAppenders.get(0));
        ((Logger) LoggerFactory.getLogger(NotificationDeliveryHandler.class)).detachAppender(logAppenders.get(1));
        logAppenders.clear();
    }

    private long countLogsContaining(String fragment) {
        return countLogsMatching(line -> line.contains(fragment));
    }

    private long countLogsMatching(java.util.function.Predicate<String> predicate) {
        return logAppenders.stream()
                .flatMap(appender -> appender.list.stream())
                .map(ILoggingEvent::getFormattedMessage)
                .filter(predicate)
                .count();
    }

    private static byte[] validEnvelope(String type, String eventId, String correlationId) {
        String json = """
                {"type":"%s","eventId":"%s","timestamp":"2026-09-10T12:00:00Z","traceId":"trace-1",
                "correlationId":"%s","payload":{"bookingId":"booking-1","studentOid":"student-oid-1"}}
                """.formatted(type, eventId, correlationId);
        return json.getBytes();
    }
}
