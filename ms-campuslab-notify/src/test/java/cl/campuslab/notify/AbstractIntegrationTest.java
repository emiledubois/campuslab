package cl.campuslab.notify;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;

/**
 * A real RabbitMQ broker via Testcontainers, not a mock - this slice's own acceptance
 * criteria (design doc §9 AC10-AC12) are explicitly about real dead-lettering and real
 * container ack/nack behaviour, neither of which a mocked AMQP client could prove.
 * Deliberately NOT annotated with @Testcontainers/@Container - same singleton-container
 * rationale as every other service's own AbstractIntegrationTest in this project.
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
