package cl.campuslab.notify.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.rabbitmq.client.Channel;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * AAA, Mockito-only coverage of the retry/backoff/ACK/NACK decision in isolation (design
 * doc AC10) - no broker/{@code Channel} involved beyond a mock, since the decision logic
 * itself (not the real AMQP wire protocol) is what's under test here. Real-broker
 * behaviour (ack/nack actually landing a message correctly) is covered by {@code
 * NotificationListenersIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeliveryHandlerTest {

    private static final Set<NotificationType> EMAIL_TYPES = Set.of(NotificationType.EMAIL_APPROVED);
    private static final long DELIVERY_TAG = 42L;

    @Mock
    private NotificationProcessor processor;

    @Mock
    private Channel channel;

    private Message message;

    @BeforeEach
    void setUp() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(DELIVERY_TAG);
        message = new Message("irrelevant - processor itself is mocked".getBytes(), properties);
    }

    @Test
    void handle_withPoisonMessageException_nacksImmediatelyWithZeroRetriesAndNoBackoffDelay() throws Exception {
        NotificationDeliveryHandler handler = new NotificationDeliveryHandler(processor, 3, 500, 2.0, 2000);
        given(processor.process(any(), any(), any()))
                .willThrow(new PoisonMessageException(
                        PoisonMessageException.Reason.UNPARSEABLE_ENVELOPE, "bad envelope", null));

        Instant before = Instant.now();
        handler.handle(message, channel, "q.cmd.email", EMAIL_TYPES);
        Duration elapsed = Duration.between(before, Instant.now());

        verify(processor, times(1)).process(any(), any(), any());
        verify(channel, times(1)).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
        assertThat(elapsed).isLessThan(Duration.ofMillis(400));
    }

    @Test
    void handle_withTransientFailureThenSuccess_retriesThenAcksExactlyOnce() throws Exception {
        int maxAttempts = 4;
        NotificationDeliveryHandler handler = new NotificationDeliveryHandler(processor, maxAttempts, 5, 2.0, 20);
        AtomicInteger callCount = new AtomicInteger(0);
        given(processor.process(any(), any(), any())).willAnswer(invocation -> {
            int attempt = callCount.incrementAndGet();
            if (attempt < maxAttempts) {
                throw new TransientNotificationException("simulated transient failure", "corr-1");
            }
            return "corr-1";
        });

        handler.handle(message, channel, "q.cmd.email", EMAIL_TYPES);

        verify(processor, times(maxAttempts)).process(any(), any(), any());
        verify(channel, times(1)).basicAck(DELIVERY_TAG, false);
        verify(channel, never()).basicNack(DELIVERY_TAG, false, false);
    }

    @Test
    void handle_withTransientFailureExhaustingBudget_nacksExactlyOnceToDlq() throws Exception {
        int maxAttempts = 3;
        NotificationDeliveryHandler handler = new NotificationDeliveryHandler(processor, maxAttempts, 5, 2.0, 20);
        given(processor.process(any(), any(), any()))
                .willThrow(new TransientNotificationException("always fails", "corr-2"));

        handler.handle(message, channel, "q.cmd.email", EMAIL_TYPES);

        verify(processor, times(maxAttempts)).process(any(), any(), any());
        verify(channel, times(1)).basicNack(DELIVERY_TAG, false, false);
        verify(channel, never()).basicAck(DELIVERY_TAG, false);
    }
}
