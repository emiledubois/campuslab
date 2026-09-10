package cl.campuslab.notify.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cl.campuslab.notify.AbstractIntegrationTest;
import cl.campuslab.notify.TestTopologyConfig;
import java.time.Duration;
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
 * AC10 (idempotent dedup, proven via notify's own logs, not just described), AC11
 * (poison message reaches the correct DLQ, provably), AC12 (notify keeps functioning
 * after a poison message) - all against a real, running broker.
 */
@SpringBootTest
@Import(TestTopologyConfig.class)
class NotificationListenersIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private DedupCache dedupCache;

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        rabbitAdmin.purgeQueue("q.cmd.email");
        rabbitAdmin.purgeQueue("q.cmd.prep");
        rabbitAdmin.purgeQueue("q.cmd.email.dlq");
        rabbitAdmin.purgeQueue("q.cmd.prep.dlq");

        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(NotificationProcessor.class)).addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(NotificationProcessor.class)).detachAppender(logAppender);
    }

    @Test
    void redeliveredMessage_isDedupedAndLoggedExactlyOnceAsFresh() {
        String eventId = UUID.randomUUID().toString();
        byte[] body = validEnvelope("EMAIL_APPROVED", eventId);

        publishToDirectExchange("email.send", body);
        await().atMost(Duration.ofSeconds(5)).until(() -> dedupCache.isDuplicate(eventId));

        // AC10's "redeliver the identical message" - republished directly onto the same
        // queue with the identical eventId, standing in for a broker-level redelivery.
        rabbitTemplate.send("", "q.cmd.email", new Message(body, new MessageProperties()));
        await().atMost(Duration.ofSeconds(5)).until(() -> countLogsContaining("DUPLICATE_SKIPPED") >= 1);

        assertThat(countLogsContaining("eventId=[" + eventId + "]"))
                .isGreaterThanOrEqualTo(2);
        assertThat(countLogsMatching(line -> line.contains(eventId) && line.contains("outcome=[FRESH]")))
                .isEqualTo(1);
        assertThat(countLogsMatching(line -> line.contains(eventId) && line.contains("outcome=[DUPLICATE_SKIPPED]")))
                .isEqualTo(1);
    }

    @Test
    void notValidJson_onEmailQueue_landsInEmailDlqNotRedeliveredToEmailQueue() {
        rabbitTemplate.send("", "q.cmd.email", new Message("not valid json".getBytes(), new MessageProperties()));

        await().atMost(Duration.ofSeconds(5)).until(() -> depthOf("q.cmd.email.dlq") == 1);
        assertThat(depthOf("q.cmd.email")).isZero();
    }

    @Test
    void unrecognizedType_onPrepQueue_landsInPrepDlq() {
        byte[] body = validEnvelope("FOO", UUID.randomUUID().toString());
        rabbitTemplate.send("", "q.cmd.prep", new Message(body, new MessageProperties()));

        await().atMost(Duration.ofSeconds(5)).until(() -> depthOf("q.cmd.prep.dlq") == 1);
        assertThat(depthOf("q.cmd.prep")).isZero();
    }

    @Test
    void afterAPoisonMessage_theNextValidMessageIsStillProcessedNormally() {
        rabbitTemplate.send("", "q.cmd.email", new Message("not valid json".getBytes(), new MessageProperties()));
        await().atMost(Duration.ofSeconds(5)).until(() -> depthOf("q.cmd.email.dlq") == 1);

        String freshEventId = UUID.randomUUID().toString();
        rabbitTemplate.send("", "q.cmd.email", new Message(validEnvelope("EMAIL_ROOM_READY", freshEventId), new MessageProperties()));

        await().atMost(Duration.ofSeconds(5)).until(() -> dedupCache.isDuplicate(freshEventId));
        assertThat(countLogsMatching(line -> line.contains(freshEventId) && line.contains("outcome=[FRESH]"))).isEqualTo(1);
    }

    private void publishToDirectExchange(String routingKey, byte[] body) {
        rabbitTemplate.send("cmd.direct", routingKey, new Message(body, new MessageProperties()));
    }

    private long depthOf(String queueName) {
        var properties = rabbitAdmin.getQueueProperties(queueName);
        return properties != null ? ((Number) properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT)).longValue() : -1;
    }

    private long countLogsContaining(String fragment) {
        return countLogsMatching(line -> line.contains(fragment));
    }

    private long countLogsMatching(java.util.function.Predicate<String> predicate) {
        return logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(predicate)
                .count();
    }

    private static byte[] validEnvelope(String type, String eventId) {
        String json = """
                {"type":"%s","eventId":"%s","timestamp":"2026-09-10T12:00:00Z","traceId":"trace-1",
                "correlationId":"booking-1","payload":{"bookingId":"booking-1","studentOid":"student-oid-1"}}
                """.formatted(type, eventId);
        return json.getBytes();
    }
}
