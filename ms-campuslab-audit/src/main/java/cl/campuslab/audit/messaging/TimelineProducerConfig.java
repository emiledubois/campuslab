package cl.campuslab.audit.messaging;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Productor de {@code audit.timeline} (audit-timeline-compacted.md §5.3.5). Solo el
 * template es bean; la factory se construye aqui dentro para no agregar un segundo
 * {@code ProducerFactory<String, Object>} que vuelva ambiguos los puntos de inyeccion de
 * {@link KafkaConsumerConfig}.
 */
@Configuration
public class TimelineProducerConfig {

    @Bean
    public KafkaTemplate<String, Object> timelineKafkaTemplate(KafkaConsumerProperties properties) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class,
                // No filtrar el nombre de clase de audit a otro servicio (§7 A08).
                JsonSerializer.ADD_TYPE_INFO_HEADERS, false,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true,
                ProducerConfig.RETRIES_CONFIG, 3,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
                // Debe ser >= linger.ms + request.timeout.ms; acotado al .get(3s) del publicador.
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }
}
