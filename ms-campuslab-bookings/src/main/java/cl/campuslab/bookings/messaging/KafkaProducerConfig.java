package cl.campuslab.bookings.messaging;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Producer-only wiring for {@code bookings.events} (design doc §5.3) - bookings never
 * declares topology (kafka-admin's job), so this is publish-only configuration. {@code
 * acks=all}/{@code enable.idempotence=true}/bounded retries give real delivery
 * durability (design doc §2.2's "stronger than slice 5's fire-and-forget" decision);
 * {@code request.timeout.ms}/{@code max.block.ms} are set well under the 3-second
 * synchronous wait at the call site so a genuinely unreachable Kafka fails fast.
 */
@Configuration
@EnableConfigurationProperties(KafkaProducerProperties.class)
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, Object> bookingEventStreamProducerFactory(KafkaProducerProperties properties) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.RETRIES_CONFIG, 3,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) properties.requestTimeoutMs(),
                ProducerConfig.MAX_BLOCK_MS_CONFIG, (int) properties.maxBlockMs());
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, Object> bookingEventStreamKafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }
}
