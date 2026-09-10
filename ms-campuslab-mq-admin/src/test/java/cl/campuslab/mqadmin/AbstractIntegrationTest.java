package cl.campuslab.mqadmin;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * A real RabbitMQ broker via Testcontainers, not a mock - this slice's own acceptance
 * criteria (design doc §9 AC1-AC7) are explicitly about idempotent topology declaration,
 * real queue depth/consumer-count inspection and real message movement between queues,
 * none of which a mocked AMQP client could prove.
 *
 * Deliberately NOT annotated with @Testcontainers/@Container - same singleton-container
 * rationale as ms-campuslab-catalog's own AbstractIntegrationTest (started once in a
 * static initializer, shared across every test class that extends this, never explicitly
 * stopped - the Ryuk reaper cleans it up at JVM exit).
 */
public abstract class AbstractIntegrationTest {

    protected static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.3.5-management");

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
}
