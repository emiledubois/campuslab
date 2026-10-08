package cl.campuslab.notify.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.campuslab.notify.AbstractIntegrationTest;
import cl.campuslab.notify.NotifyApplication;
import cl.campuslab.notify.TestDispatcherConfig;
import cl.campuslab.notify.TestTopologyConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * QA-added (not part of the developer's diff) - design doc mq-names-domain-separation.md
 * §9 AC13: deliberately misconfigures {@code notify.messaging.queue.email} to a
 * non-existent queue name (one {@code TestTopologyConfig} never declares on the broker)
 * and confirms the drift is caught loudly, not silently swallowed.
 *
 * Empirically, this config-drift is caught even more loudly than §9 AC13's own prose
 * describes ("email-path assertions fail because the message is never consumed"): Spring
 * AMQP's {@code SimpleMessageListenerContainer} has {@code missingQueuesFatal=true} by
 * default, so a listener configured to a queue name the broker was never told to declare
 * fails container startup outright with {@code QueuesNotAvailableException} /
 * {@code 404 NOT_FOUND}, which aborts the whole Spring context refresh. That is a
 * strictly stronger "caught, not silently swallowed" than a timeout - this test proves
 * that stronger failure mode directly, bypassing the JUnit Spring-context-cache extension
 * (which would otherwise swallow/rethrow the failure in a way that cannot be asserted on
 * inside a normal {@code @Test} method body) by booting the application manually.
 */
class ConfigDriftDetectionTest extends AbstractIntegrationTest {

    @Test
    void misconfiguredEmailQueueName_failsContextStartupLoudly_insteadOfSilentlyDriftingFromMqTopology() {
        // System properties outrank application.yml in Spring's property-source order, so
        // this (unlike SpringApplicationBuilder#properties(...), which binds at the lowest
        // priority "defaultProperties" source) actually overrides src/test/resources/
        // application.yml's correct "q.cmd.email" value with the deliberate typo below.
        System.setProperty("notify.messaging.queue.email", "q.cmd.email.TYPO-DOES-NOT-EXIST");
        try {
            SpringApplicationBuilder builder = new SpringApplicationBuilder(NotifyApplication.class)
                    .properties(
                            "spring.rabbitmq.host=" + RABBIT.getHost(),
                            "spring.rabbitmq.port=" + RABBIT.getAmqpPort(),
                            "spring.rabbitmq.username=" + RABBIT.getAdminUsername(),
                            "spring.rabbitmq.password=" + RABBIT.getAdminPassword())
                    .sources(TestTopologyConfig.class, TestDispatcherConfig.class)
                    .web(org.springframework.boot.WebApplicationType.NONE);

            assertThatThrownBy(() -> {
                try (ConfigurableApplicationContext ignored = builder.run()) {
                    // never reached if the drift is caught, as asserted below
                }
            })
                    .hasMessageContaining("Failed to start bean")
                    .hasStackTraceContaining("q.cmd.email.TYPO-DOES-NOT-EXIST")
                    .hasStackTraceContaining("NOT_FOUND");
        } finally {
            System.clearProperty("notify.messaging.queue.email");
        }
    }
}
