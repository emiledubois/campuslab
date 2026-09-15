package cl.campuslab.kafkaadmin.topology;

import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Raw {@code AdminClient} only (design doc §2 - deliberately not {@code spring-kafka}:
 * kafka-admin is topology/inspection only, never a producer or consumer of business
 * messages, mirroring mq-admin's choice of {@code RabbitAdmin} over a full messaging
 * stack). Request timeout is bounded well below the 503-mapping code's own wait so a
 * genuinely unreachable broker fails fast (design doc §3's "503: Kafka unreachable").
 *
 * {@code @Lazy} (docs/designs/aws-deployment.md Part 7) - {@code Admin.create(...)}
 * resolves and validates {@code bootstrap.servers} synchronously, inside the
 * kafka-clients library itself, not in any Spring wrapper. Left eager, a genuinely
 * unresolvable broker hostname throws a {@code BeanCreationException} during
 * {@code ApplicationContext} refresh and crashes the process before
 * {@code TopologyStartupRunner}/{@code TopologyHealthIndicator}'s own try/catch ever
 * runs. {@code @Lazy} defers the real {@code Admin.create()} call to the first actual
 * method invocation on this bean, which happens inside {@code TopologyStartupRunner}'s
 * call chain - already wrapped in existing, already-tested try/catch blocks
 * ({@code TopologyInspectionService#createAndVerifyAtStartup}, {@code #verify}) built to
 * swallow exactly this class of failure. Mirrors, by the only mechanism Kafka's raw
 * {@code AdminClient} allows, the same "no network-touching call at bean construction
 * time" property mq-admin gets for free from Spring AMQP's
 * {@code CachingConnectionFactory}.
 *
 * <p>{@code @Lazy} here alone is not sufficient - every constructor parameter typed
 * {@code Admin} anywhere in this service must also be annotated {@code @Lazy}, or Spring's
 * {@code preInstantiateSingletons()} resolves it eagerly regardless of this bean's own
 * laziness (docs/designs/aws-deployment.md Revision 4). Nothing enforces this
 * structurally - check this Javadoc before adding a fifth {@code Admin} consumer.
 */
@Configuration
@EnableConfigurationProperties(KafkaAdminProperties.class)
public class KafkaAdminClientConfig {

    @Bean(destroyMethod = "close")
    @Lazy
    public Admin kafkaAdminClient(KafkaAdminProperties properties) {
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        return Admin.create(config);
    }
}
