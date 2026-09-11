package cl.campuslab.kafkaadmin;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/**
 * A real, single-broker Kafka via Testcontainers, not a mock - this slice's own
 * acceptance criteria (design doc §9 AC1-AC3) are explicitly about idempotent topic
 * creation, real config verification and real broker connectivity, none of which a
 * mocked AdminClient could prove. Deliberately NOT annotated with @Testcontainers/
 * @Container - same singleton-container rationale as mq-admin's own AbstractIntegrationTest
 * (started once in a static initializer, shared across every test class that extends
 * this, never explicitly stopped - the Ryuk reaper cleans it up at JVM exit).
 */
public abstract class AbstractIntegrationTest {

    protected static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.9.9");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
