package cl.campuslab.kafkaadmin.topology;

import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Raw {@code AdminClient} only (design doc §2 - deliberately not {@code spring-kafka}:
 * kafka-admin is topology/inspection only, never a producer or consumer of business
 * messages, mirroring mq-admin's choice of {@code RabbitAdmin} over a full messaging
 * stack). Request timeout is bounded well below the 503-mapping code's own wait so a
 * genuinely unreachable broker fails fast (design doc §3's "503: Kafka unreachable").
 */
@Configuration
@EnableConfigurationProperties(KafkaAdminProperties.class)
public class KafkaAdminClientConfig {

    @Bean(destroyMethod = "close")
    public Admin kafkaAdminClient(KafkaAdminProperties properties) {
        Map<String, Object> config = Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers(),
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5000);
        return Admin.create(config);
    }
}
