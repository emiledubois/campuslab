package cl.campuslab.notify.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import cl.campuslab.notify.AbstractIntegrationTest;
import cl.campuslab.notify.TestDispatcherConfig;
import cl.campuslab.notify.TestTopologyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * AC8 - proves {@code spring.rabbitmq.listener.simple.{prefetch,concurrency,max-concurrency}}
 * (design doc §5.3) are actually applied to the live container backing each {@code
 * @RabbitListener}, not merely present in YAML with no effect. {@code
 * SimpleMessageListenerContainer} exposes {@code getPrefetchCount()} as {@code
 * protected} and has no public getter at all for concurrency - {@link
 * ReflectionTestUtils} reads the backing fields directly, the standard Spring Test way
 * to assert on framework-internal state the framework itself doesn't expose publicly.
 * {@code @Import} set is deliberately identical to {@code NotificationListenersIntegrationTest}'s
 * so Spring's test context cache reuses the exact same {@code ApplicationContext} - a
 * different {@code @Import} set would get its own, separately-cached context whose real
 * {@code @RabbitListener} beans would then keep running (Spring Test never proactively
 * closes a cached context) and compete for messages against the other test class's
 * listeners on the same shared Testcontainers broker, corrupting both.
 */
@SpringBootTest
@Import({TestTopologyConfig.class, TestDispatcherConfig.class})
class NotificationListenerContainerConfigTest extends AbstractIntegrationTest {

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    @Test
    void emailAndPrepListenerContainers_useConfiguredPrefetchAndConcurrency() {
        assertThat(registry.getListenerContainers()).isNotEmpty();

        for (MessageListenerContainer container : registry.getListenerContainers()) {
            assertThat(container).isInstanceOf(SimpleMessageListenerContainer.class);
            assertThat((Integer) ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(1);
            assertThat((Integer) ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(3);
            assertThat((Integer) ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(5);
        }
    }
}
