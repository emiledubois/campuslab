package cl.campuslab.report.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import cl.campuslab.report.AbstractIntegrationTest;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * docs/designs/aws-deployment.md Part 9 / AC23 - proves report's {@code @KafkaListener}
 * container no longer crashes {@code ApplicationContext} refresh against a completely
 * unresolvable broker, and that it genuinely stays stopped (not merely that the context
 * happened not to crash for some other reason). Points {@code kafka.bootstrap-servers} at
 * a hostname that does not resolve in DNS at all - the same value
 * {@code KafkaAdminClientLazyInitTest} uses for kafka-admin's equivalent fix.
 *
 * <p>Does not need to prove eventual recovery once a broker appears later inside this same
 * test - that is covered at the unit level by {@code KafkaListenerStartupRetryTaskTest} and
 * at the real-infrastructure level by AC20 (Java's own DNS-resolution caching makes an
 * "unresolvable-then-resolvable" transition impractical to construct reliably inside one
 * JVM test process).
 *
 * <p>{@link KafkaListenerStartupRetryTask} is mocked out here deliberately: its own
 * {@code @Scheduled} tick fires essentially immediately after context refresh (Part 9's own
 * "unchanged, normal case" trace) and, against this test's unresolvable host, blocks for
 * several real seconds inside {@code KafkaConsumer}'s own DNS-resolution attempt with the
 * outer container's {@code isRunning()} transiently {@code true} the whole time (the very
 * landmine Part 9 documents) - a real race against this test's own assertion that has nothing
 * to do with what this test exists to prove (that {@code autoStartup(false)} stops Spring
 * Kafka's own default of auto-starting the container during context refresh). The retry
 * task's own retry/reset behavior is proven in isolation, without this race, by
 * {@code KafkaListenerStartupRetryTaskTest}.
 */
@SpringBootTest
class KafkaListenerLazyStartupTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void unresolvableBroker(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", () -> "this-host-does-not-exist.invalid:9092");
    }

    @Autowired
    private ConfigurableApplicationContext applicationContext;

    @Autowired
    private KafkaListenerEndpointRegistry listenerRegistry;

    // Avoids an unrelated real network call to a fake issuer URL during context refresh -
    // this test is about listener container startup timing, not JWT validation.
    @MockBean
    private JwtDecoder jwtDecoder;

    // See class Javadoc: isolates this test's own assertion from the retry task's
    // independent, already separately-tested concurrent start attempt.
    @MockBean
    private KafkaListenerStartupRetryTask kafkaListenerStartupRetryTask;

    @Test
    void contextRefresh_withUnresolvableBootstrapServers_startsAndContainerStaysStopped() {
        assertThat(applicationContext.isActive()).isTrue();

        Collection<MessageListenerContainer> containers = listenerRegistry.getListenerContainers();
        assertThat(containers).isNotEmpty();
        assertThat(containers).allSatisfy(container -> assertThat(container.isRunning()).isFalse());
    }
}
